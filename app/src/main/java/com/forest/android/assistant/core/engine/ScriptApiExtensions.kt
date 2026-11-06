package com.forest.android.assistant.core.engine

/** Additional public script helpers kept separate from the core runtime prelude. */
internal object ScriptApiExtensions {
    val PRELUDE = """
// ==================== Extended image and polling APIs ====================
function __pollNumber(value, fallback, allowZero) {
    if (value === undefined || value === null) return fallback;
    var n = Number(value);
    if (!isFinite(n) || n < 0 || (!allowZero && n === 0)) {
        throw new TypeError("无效的轮询参数: " + value);
    }
    return Math.floor(n);
}
function __pollOptions(options, defaults) {
    options = options || {};
    if (typeof options !== 'object') throw new TypeError("options 必须是对象");
    return {
        timeoutMs: __pollNumber(options.timeoutMs, 0, true),
        intervalMs: Math.max(50, __pollNumber(options.intervalMs, defaults.intervalMs, true)),
        missLimit: __pollNumber(options.missLimit, 0, true),
        confirmCount: Math.max(1, __pollNumber(options.confirmCount, 1, false)),
        maxAttempts: __pollNumber(options.maxAttempts, 0, true),
        maxClicks: __pollNumber(options.maxClicks, 0, true),
        delayMs: __pollNumber(options.delayMs, 0, true),
        name: options.name == null ? '' : String(options.name),
        onRetry: options.onRetry,
        options: options
    };
}
function __pollResult(ok, reason, name, attempts, misses, elapsedMs, value, lastError, clicks) {
    return {
        ok: !!ok, reason: reason, name: name,
        attempts: attempts, misses: misses, clicks: clicks || 0,
        elapsedMs: elapsedMs, value: value == null ? null : value,
        lastError: lastError || null
    };
}
function __callRetry(callback, reason, state) {
    if (typeof callback !== 'function') return;
    var result = callback(reason, state);
    if (result && typeof result.then === 'function') {
        throw new TypeError("onRetry 必须是同步函数");
    }
}
function __ocrFindAny(image, texts, options) {
    var list = [];
    if (texts && typeof texts.length === 'number' && typeof texts !== 'string') {
        for (var i = 0; i < texts.length; i++) {
            if (texts[i] !== null && texts[i] !== undefined) list.push(String(texts[i]));
        }
    }
    if (!list.length) return { value: null, error: 'invalid_query' };
    if (options && options.regions !== undefined && options.region !== undefined) {
        return { value: null, error: 'invalid_region' };
    }
    var regions;
    if (options && options.regions !== undefined) {
        if (!options.regions || typeof options.regions.length !== 'number' ||
            typeof options.regions === 'string' || options.regions.length === 0) {
            return { value: null, error: 'invalid_region' };
        }
        regions = options.regions;
    } else {
        regions = [options && options.region ? options.region : null];
    }
    for (var r = 0; r < regions.length; r++) {
        var region = regions[r];
        if (region !== null && region !== undefined &&
            (typeof region.length !== 'number' || typeof region === 'string' || region.length < 2)) {
            return { value: null, error: 'invalid_region' };
        }
        if (region) for (var j = 0; j < Math.min(region.length, 4); j++) {
            if (typeof region[j] !== 'number' || !isFinite(region[j])) {
                return { value: null, error: 'invalid_region' };
            }
        }
        var response = __ocr_find_any_checked(
            __img(image), list,
            region && region.length > 0 ? region[0] : -1,
            region && region.length > 1 ? region[1] : -1,
            region && region.length > 2 ? region[2] : -1,
            region && region.length > 3 ? region[3] : -1
        );
        if (response[0] === 'ocr_error' || response[0] === 'invalid_image') {
            return { value: null, error: response[0] };
        }
        if (response[0] === 'matched') {
            var item = __ocrItem(response[1]);
            item.matched = response[1][6];
            item.regionIndex = options && options.regions !== undefined ? r : -1;
            return { value: item, error: null };
        }
    }
    return { value: null, error: null };
}

// Find any keyword across ordered regions. Each region is read from the same Image.
ocr.findTextAny = function(image, texts, options) {
    var found = __ocrFindAny(image, texts, options);
    return found.value;
};

function __prepareTemplate(tpl) {
    var owned = typeof tpl === 'string';
    var image = owned ? images.read(tpl) : tpl;
    if (!image || images.width(image) <= 0 || images.height(image) <= 0) {
        if (owned && image) images.release(image);
        image = null;
    }
    return { image: image, owned: owned };
}
function __checkSyncCallback(callback, label) {
    if (typeof callback !== 'function') throw new TypeError(label + " 必须是函数");
}

// General screenshot polling. The callback receives the current owned frame and must return synchronously.
function __pollScreen(check, options, mode) {
    __checkSyncCallback(check, 'check');
    var cfg = __pollOptions(options, { intervalMs: 500 });
    if (cfg.onRetry !== undefined && typeof cfg.onRetry !== 'function') {
        throw new TypeError("onRetry 必须是函数");
    }
    var started = Date.now(), deadline = cfg.timeoutMs > 0 ? started + cfg.timeoutMs : Infinity;
    var attempts = 0, misses = 0, confirmed = 0, clicks = 0, lastError = null;
    var value = null;
    if (!__capture_ready() && !__request_capture()) {
        return __pollResult(false, 'capture_error', cfg.name, 0, 0, Date.now() - started, null, 'capture_error', clicks);
    }
    while (true) {
        var cycle = Date.now(), frame = null, probe = null, probeReason = 'capture_error';
        attempts++;
        frame = captureScreen();
        var probeError = null;
        if (frame) {
            try {
                probe = check(frame, {
                    attempts: attempts, misses: misses, clicks: clicks,
                    elapsedMs: Date.now() - started, name: cfg.name
                });
                if (probe && typeof probe.then === 'function') {
                    throw new TypeError("check 必须是同步函数");
                }
                if (probe && probe.__pollError) {
                    probeError = String(probe.__pollError);
                    probe = null;
                }
                probeReason = 'miss';
            } finally {
                images.release(frame);
            }
            if (probeError) {
                confirmed = 0;
                lastError = probeError;
                probeReason = probeError;
            } else if (probe) {
                lastError = null;
                value = probe;
                misses = 0;
                confirmed++;
                probeReason = confirmed >= cfg.confirmCount ? 'matched' : 'confirming';
            } else {
                confirmed = 0;
                misses++;
            }
        } else {
            confirmed = 0;
            lastError = 'capture_error';
        }

        if (probe && confirmed >= cfg.confirmCount) {
            if (mode === 'wait') {
                return __pollResult(true, 'matched', cfg.name, attempts, misses, Date.now() - started, value, null, clicks);
            }
            if (mode === 'click-once' || mode === 'watch' || mode === 'click-until-gone') {
                if (click(value.x, value.y)) {
                    clicks++;
                    misses = 0;
                    confirmed = 0;
                    lastError = null;
                    if (mode === 'click-once') {
                        if (cfg.delayMs > 0) sleep(cfg.delayMs);
                        return __pollResult(true, 'clicked', cfg.name, attempts, misses, Date.now() - started, value, null, clicks);
                    }
                    if (mode === 'watch' && cfg.maxClicks > 0 && clicks >= cfg.maxClicks) {
                        if (cfg.delayMs > 0) sleep(cfg.delayMs);
                        return __pollResult(true, 'max_clicks', cfg.name, attempts, misses, Date.now() - started, value, null, clicks);
                    }
                    if (mode === 'click-until-gone' && cfg.maxClicks > 0 && clicks >= cfg.maxClicks) {
                        if (cfg.delayMs > 0) sleep(cfg.delayMs);
                        return __pollResult(true, 'max_clicks', cfg.name, attempts, misses, Date.now() - started, value, null, clicks);
                    }
                    if (cfg.delayMs > 0) sleep(cfg.delayMs);
                } else {
                    confirmed = 0;
                    lastError = 'click_failed';
                    probeReason = 'click_failed';
                }
            }
        }

        if (probeError === 'invalid_query' || probeError === 'invalid_region') {
            return __pollResult(false, 'invalid_argument', cfg.name, attempts, misses, Date.now() - started, null, probeError, clicks);
        }
        if (probeError === 'ocr_error' || probeError === 'invalid_image') {
            return __pollResult(false, probeError, cfg.name, attempts, misses, Date.now() - started, null, probeError, clicks);
        }
        if (mode === 'click-until-gone' && !probe && !probeError && misses >= cfg.confirmCount) {
            return __pollResult(true, 'gone', cfg.name, attempts, misses, Date.now() - started, value, lastError, clicks);
        }
        if (mode === 'watch' && cfg.missLimit > 0 && misses >= cfg.missLimit) {
            return __pollResult(false, 'miss_limit', cfg.name, attempts, misses, Date.now() - started, value, lastError, clicks);
        }
        if (mode !== 'watch' && mode !== 'click-until-gone' && cfg.missLimit > 0 && misses >= cfg.missLimit) {
            return __pollResult(false, 'miss_limit', cfg.name, attempts, misses, Date.now() - started, value, lastError, clicks);
        }
        if ((cfg.maxAttempts > 0 && attempts >= cfg.maxAttempts) || Date.now() >= deadline) {
            var reason = cfg.maxAttempts > 0 && attempts >= cfg.maxAttempts ? 'attempt_limit' : 'timeout';
            return __pollResult(false, reason, cfg.name, attempts, misses, Date.now() - started, value, lastError, clicks);
        }
        __callRetry(cfg.onRetry, probeReason, {
            attempts: attempts, misses: misses, clicks: clicks,
            elapsedMs: Date.now() - started, name: cfg.name, value: value,
            lastError: lastError
        });
        var remaining = cfg.intervalMs - (Date.now() - cycle);
        if (remaining > 0) sleep(remaining);
    }
}

function waitScreen(check, options) {
    return __pollScreen(check, options, 'wait');
}
function waitTextAny(texts, options) {
    return __pollScreen(function(frame) {
        var result = __ocrFindAny(frame, texts, options || {});
        if (result.error) return { __pollError: result.error };
        return result.value;
    }, options, 'wait');
}
function waitText(text, options) {
    return waitTextAny([text], options);
}
function waitTextClick(texts, timeout, interval, options) {
    var cfg = {};
    options = options || {};
    for (var key in options) if (Object.prototype.hasOwnProperty.call(options, key)) cfg[key] = options[key];
    var t = timeout === undefined || timeout === null ? NaN : Number(timeout);
    if (!isFinite(t) || t < 0) t = NaN;
    cfg.timeoutMs = isFinite(t) && t > 0 ? t * 1000 : 0;
    if (t === 0) cfg.maxAttempts = 1;
    var i = interval === undefined || interval === null ? 1 : Number(interval);
    cfg.intervalMs = (!isFinite(i) || i <= 0) ? 1000 : i * 1000;
    cfg.delayMs = __pollNumber(options.delay, 0, true);
    var result = __pollScreen(function(frame) {
        var found = __ocrFindAny(frame, texts, options);
        if (found.error) return { __pollError: found.error };
        return found.value;
    }, cfg, 'click-once');
    return result.ok;
}

function watchText(texts, options) {
    options = options || {};
    var cfg = {};
    for (var key in options) if (Object.prototype.hasOwnProperty.call(options, key)) cfg[key] = options[key];
    cfg.delayMs = __pollNumber(options.delayMs, 0, true);
    return __pollScreen(function(frame) {
        var found = __ocrFindAny(frame, texts, options);
        if (found.error) return { __pollError: found.error };
        return found.value;
    }, cfg, 'watch');
}

function __clickImageUntilGone(tpl, options) {
    var prepared = __prepareTemplate(tpl);
    if (!prepared.image) return __pollResult(false, 'template_error', options && options.name || '', 0, 0, 0, null, 'template_error', 0);
    var cfg = {};
    options = options || {};
    for (var key in options) if (Object.prototype.hasOwnProperty.call(options, key)) cfg[key] = options[key];
    cfg.confirmCount = __pollNumber(options.confirmCount, 3, false);
    try {
        return __pollScreen(function(frame) {
            return images.findImage(frame, prepared.image, options);
        }, cfg, 'click-until-gone');
    } finally {
        if (prepared.owned) images.release(prepared.image);
    }
}
function clickImageUntilGone(tpl, options) {
    return __clickImageUntilGone(tpl, options);
}

function retry(action, options) {
    __checkSyncCallback(action, 'action');
    options = options || {};
    var cfg = __pollOptions(options, { intervalMs: 500 });
    if (cfg.onRetry !== undefined && typeof cfg.onRetry !== 'function') throw new TypeError("onRetry 必须是函数");
    var started = Date.now(), deadline = cfg.timeoutMs > 0 ? started + cfg.timeoutMs : Infinity;
    var attempts = 0, lastValue = null;
    if (options.maxAttempts === undefined || options.maxAttempts === null) cfg.maxAttempts = 3;
    while (true) {
        attempts++;
        lastValue = action(attempts, {
            attempts: attempts, misses: attempts - 1, clicks: 0,
            elapsedMs: Date.now() - started, name: cfg.name,
            value: lastValue, lastError: null
        });
        if (lastValue && typeof lastValue.then === 'function') throw new TypeError("action 必须是同步函数");
        if (lastValue) return __pollResult(true, 'completed', cfg.name, attempts, 0, Date.now() - started, lastValue, null, 0);
        if ((cfg.maxAttempts > 0 && attempts >= cfg.maxAttempts) || Date.now() >= deadline) {
            var reason = cfg.maxAttempts > 0 && attempts >= cfg.maxAttempts ? 'attempt_limit' : 'timeout';
            return __pollResult(false, reason, cfg.name, attempts, 0, Date.now() - started, lastValue, null, 0);
        }
        __callRetry(cfg.onRetry, 'action_failed', {
            attempts: attempts, misses: attempts, clicks: 0, elapsedMs: Date.now() - started,
            name: cfg.name, value: lastValue, lastError: null
        });
        if (cfg.intervalMs > 0) sleep(cfg.intervalMs);
    }
}

images.width = function(image) { return __img_width(__img(image)); };
images.height = function(image) { return __img_height(__img(image)); };

images.resize = function(image, width, height, options) {
    options = options || {};
    var filter = options.filter === undefined ? true : !!options.filter;
    var id = __img_resize(__img(image), width, height, filter);
    return __wrapImage(id);
};
images.matchTemplate = function(image, tpl, options) {
    options = options || {};
    var owned = typeof tpl === 'string';
    var template = owned ? images.read(tpl) : tpl;
    if (!template) return [];
    try {
        var r = __regionArgs(options);
        var raw = __img_match_details(__img(image), __img(template),
            __opt(options, 'threshold', 0.9), r[0], r[1], r[2], r[3], __opt(options, 'max', 5), __opt(options, 'scales', [1]));
        var out = [];
        if (raw) for (var i = 0; i < raw.length; i++) {
            var m = raw[i];
            out.push({ x: m[0], y: m[1], score: m[2],
                bounds: { left: m[3], top: m[4], right: m[5], bottom: m[6] } });
        }
        return out;
    } finally {
        if (owned) images.release(template);
    }
};
images.findImageDetails = function(image, tpl, options) {
    var one = {};
    options = options || {};
    for (var key in options) if (Object.prototype.hasOwnProperty.call(options, key)) one[key] = options[key];
    one.max = 1;
    var matches = images.matchTemplate(image, tpl, one);
    return matches.length ? matches[0] : null;
};
""".trimIndent()
}
