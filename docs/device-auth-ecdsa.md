# 设备认证（ECDSA 许可证）设计方案

更新日期：2026-10-22
状态：首版离线授权方案已定稿；密码学格式与测试向量已复核，尚未落地到代码。

## 1. 目标与非目标

一机一证：每台设备一张许可证，APK 内只放**公钥**，因此即使 APK 被反编译也**无法伪造**许可证。

| 攻击 | 结果 | 机制 |
| --- | --- | --- |
| 把**未修改的** APK 拷到另一台设备使用 | 挡住 | 许可证内嵌 80 bit 设备标签 |
| 修改许可证里的到期日 / 功能位 | 挡住 | 改任意字节签名即失效 |
| 自行构造许可证 | 挡住 | 无私钥无法生成 ECDSA 签名 |
| 反编译提取密钥做注册机 | **挡住** | APK 内只有公钥，公钥不能用于签名 |
| Frida / 动态 hook 绕过校验 | 挡不住 | 需加固与多点校验（见第 12 节） |
| 改系统时间续期 | 只能部分挡住 | `maxSeenDay` 只能防“已经观察到较晚日期后的回拨”，不能防时间冻结（见第 9 节） |
| 清除应用数据 / 恢复旧备份 | 挡不住 | 本地单调时钟会被重置；必须排除备份并接受该边界 |
| 离线撤销 | 挡不住 | 设备看不到新撤销表；首版不做在线撤销 |

**非目标**：不防 root、改包、Frida、模拟器多开共享设备标识，也不提供“绝对不可破解”的保证。本方案解决的是**签名私钥泄漏**这个最致命的问题；真正的护城河仍是服务端能力（任务模板更新、云同步、脚本市场）。

与对称 HMAC 短码方案的核心差异：**签名私钥永不进入客户端**。代价是许可证约 120 字符，无法手输（见第 7 节）。

## 2. 算法选型

| 项 | 取值 | 理由 |
| --- | --- | --- |
| 签名算法 | ECDSA P-256（prime256v1 / secp256r1）+ SHA-256 | `minSdk 24` 上 `KeyFactory("EC")`、`Signature("SHA256withECDSA")` 原生可用，零依赖 |
| 签名编码 | DER | Java 默认 DER；Node 必须显式 `dsaEncoding: 'der'` 固定行为（Node v24 实测默认也是 DER，但不要依赖默认值） |
| 私钥 | PKCS#8 PEM，仅存于发证机 | 不入仓库、不入 CI 制品 |
| 公钥 | SPKI PEM，内置 APK | 可公开，泄漏无风险 |
| 版本字节 | `ver = (keyId << 4) \| formatVersion` | 同时支持公钥轮换与格式演进；当前 `0x01` 表示 keyId=0、format=1 |
| 文本编码 | Base64URL，无 padding | Node `base64url` 与 Java 端一致 |

不使用 Ed25519：Android 原生 Ed25519 需 API 33+，本项目 `minSdk 24` 只能引入 BouncyCastle（约 +3MB）。RSA 也能零依赖，但签名更长、许可证更大；P-256 是体积与兼容性最合适的零依赖方案。

### Base64 的 Android 兼容性

`java.util.Base64` 从 **Android API 26** 才可用，而项目 `minSdk = 24`，当前也没有配置 core library desugaring。`android.util.Base64` 虽然 API 1 就有，但会让 `License.kt` 依赖 Android，破坏纯 JVM 单测。

因此推荐：

- 在 `core/device/Base64Url.kt` 实现一个约 30 行的严格 Base64URL 编解码器（纯 Kotlin，API 24 可用，JVM 可测）；
- 或者把平台 Base64 放在 Android 适配层，`License.kt` 只接收 `ByteArray`，由测试使用 `java.util.Base64`。

首版采用前者，避免 API 26 限制与 Robolectric。

## 3. 设备唯一值与设备码

```
androidId  = Settings.Secure.ANDROID_ID 的 16 位十六进制串（统一转小写）
digest     = SHA-256(androidId + "|" + applicationId)        // 32 字节
deviceCode = Base32(digest, 16)                              // 规范串：16 字符，无连字符
deviceTag  = SHA-256("TAG1|" + deviceCode)[0..10]            // 80 bit，写入许可证
```

展示串：`S8RK-03VF-8RFR-5BNB`（4 字符一组，仅用于界面与客户上报）。

- 原始唯一值取 `Settings.Secure.ANDROID_ID`：免权限、无需用户同意、全版本可用。
- `deviceCode` 只用于界面展示与客户上报，**不含原始 `ANDROID_ID`**。
- `deviceTag` 由 `deviceCode` 派生而非直接取 `digest` 前 10 字节，好处是**发证端只需要设备码，不需要客户的 `ANDROID_ID`**，隐私更好。
- 前缀 `"TAG1"` 做域分隔，避免与其它哈希用途混用。

Base32 字母表采用 Crockford（去掉 `I L O U`）：`0123456789ABCDEFGHJKMNPQRSTVWXYZ`。

### 输入规范化（必须实现）

设备码输入允许容错，顺序固定：

1. 去掉首尾空白；
2. 转大写；
3. 去掉空白与连字符 `-`（只允许这两类分隔符）；
4. `I`、`L` → `1`，`O` → `0`，`U` → `V`；
5. 结果必须**恰好 16 位**，且全部属于 Crockford 字母表；否则判 `DEVICE_CODE_INVALID`。

不要静默丢弃任意非字母数字字符：客户把 `-` 抄成 `/`、把中文标点混进来时，应明确报错，而不是猜一个设备码。

### `ANDROID_ID` 的读取与校验

1. `Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)` 可能返回 `null`、`unknown`、空串或非 16 位十六进制。
2. 只接受 `[0-9a-fA-F]{16}`；统一转小写后再参与哈希。
3. 以下值视为“无法绑定”，fail-closed，不允许激活：
   - `unknown`、空串、`null`；
   - `0000000000000000`；
   - `9774d56d682e549c`（旧模拟器经典值，仅第 10 节测试向量使用）；
   - `ffffffffffffffff`。
4. 读取失败时状态为 `UNBOUND(DEVICE_UNAVAILABLE)`，提示“无法读取设备标识，请联系售后”，**不进入试用**。

首版不实现试用。若业务需要试用，必须单独设计试用状态、时限与防篡改策略；不要把它混进许可证状态机。

### `ANDROID_ID` 的已知约束（必须写进售后 SOP）

1. Android 8.0+ 起与**应用签名证书 + 用户**绑定：换 keystore 重新签名，设备码会变。
2. **Play App Signing 轮换签名密钥也会改变设备码**；不要在没有换证计划时轮换。
3. 调试包与正式包签名不同，设备码也不同；发证时必须确认包类型。
4. `applicationId` 是设备码哈希输入的一部分；改包名/应用 ID 会让所有设备码变化。
5. 恢复出厂设置、换机、多用户、工作资料都会改变设备码，需要重新发证。
6. MuMu 等多开实例各自独立；**克隆实例可能共享同一 `ANDROID_ID`**，同一许可证会被多开共用。
7. root / Xposed / 改机工具可以伪造 `ANDROID_ID`；本方案不防。
8. 当前 CI 每次用随机 debug key 构建，**同一设备的设备码会随每次构建变化**；在固定签名前不要发许可证。

后续如需更强的绑定，可考虑 Android Keystore 生成不可导出密钥、许可证绑定公钥并用私钥签名挑战；代价是设备需先上报公钥，且恢复出厂后密钥丢失。首版不做。

## 4. 许可证格式

```
license = base64url( header(18B) || signature(DER) )
```

header 固定 18 字节；DER 签名长度可变。实测 3,000 次 P-256 签名为 **69～72 字节**（理论上也可能更短），对应许可证 **87～90 字节**，Base64URL 后通常 **116～120 字符**。**不要按固定长度解析许可证**。

### header（18 字节，定长）

| 偏移 | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 1 | `ver` | 高 4 位 `keyId`，低 4 位 `formatVersion`；当前 `0x01` |
| 1 | 1 | `feat` | 功能位：bit0 运行 / bit1 定时 / bit2 导出 / bit3 保留 |
| 2 | 2 | `expDay` | 大端；到期日 = `2020-01-01 + expDay 天`；**`0` = 永久** |
| 4 | 4 | `licenseId` | 大端无符号；发证流水号，必须 ≥ 1，用于撤销与对账 |
| 8 | 10 | `deviceTag` | 设备标签（第 3 节） |

`expDay` 最大 65535，对应 **2199-06-06**；如果需要更远的日期，升级 `formatVersion`。

`ver` 的位布局：

```
bit 7..4  keyId          0..15，选择 APK 内置公钥
bit 3..0  formatVersion  当前 1；0 与未知值判 BAD_VERSION
```

`licenseId` 每次签发（新购、续期、补发）都必须使用**新的、唯一的值**。不要用“licenseId 不变”表示续期：撤销表按 ID 撤销，复用 ID 会把续期证一起撤掉。

### 签名

```
message   = "LIC1" || header          // 4 + 18 = 22 字节
signature = ECDSA-P256-SHA256(privateKey, message)    // DER
```

`"LIC1"` 前缀为域分隔：将来撤销表用 `"REV1"`、配置包用 `"CFG1"`，不会与许可证签名互相混淆。

### 文件与文本封装

- `.lic` 文件内容就是许可证串本身（纯文本），允许首尾空白与换行。
- 读取时先去掉 UTF-8 BOM，再去掉所有空白字符（空格、`\r`、`\n`、`\t`），然后严格解码。
- 只接受 Base64URL 字母表 `[A-Za-z0-9_-]`，允许末尾 `=` 但解码前去掉；其它字符判 `BAD_FORMAT`。
- 文本长度上限 4 KB；超过判 `BAD_FORMAT`，避免超大输入。
- 解码后长度必须落在 `18 + 8` 到 `18 + 72` 字节之间；越界判 `BAD_FORMAT`。
- 任何解码异常都不得抛到上层；统一转为 `BAD_FORMAT`。

## 5. 签发（发证端）

### 一次性初始化

```bash
node tools/keygen.cjs init --key-id 0
# → private.pem（PKCS#8，密码保护，保密）
# → public.pem（SPKI，内置 APK）
# → 打印公钥 SHA-256 指纹，发版时核对
```

- 私钥建议存离线机器并加密码保护；`public.pem` 放进 `app/src/main/assets/license_pub.pem`（keyId 0）。将来 keyId 1 使用 `license_pub_1.pem`。
- `init` 默认拒绝覆盖已有私钥；必须显式 `--force`。
- 私钥目录加入 `.gitignore`（例如 `tools/keys/`、`private*.pem`），不得提交、不得进 CI 制品。
- Windows 上也要设置仅当前用户可读的 ACL；Unix 上 `chmod 600`。

### 逐台发证

```bash
node tools/keygen.cjs issue \
  --device S8RK-03VF-8RFR-5BNB \      # 客户上报的设备码（可带连字符）
  --feat run,schedule,export \        # 功能位名称
  --expire 2026-12-31 \               # UTC 日期；或 --perpetual
  --id 10001 \                        # 发证流水号，必须唯一且 ≥ 1
  --key-id 0                          # 默认 0
  # → 输出 license 串 + license.lic
  # → 可选 --qr 生成 license.qr.png
```

- `--expire` 使用 **UTC 日期**，不要用本地时区解析；日期早于 `2020-01-01` 或晚于 `2199-06-06` 时报错。
- `--perpetual` 与 `--expire` 互斥。
- `--feat` 名称映射：`run=1`、`schedule=2`、`export=4`；未知名称报错。
- `--id` 必填；批量模式可 `--start-id` 自动分配。
- `--qr` 需要发证机安装 `qrcode`（仅开发依赖，不进入 APK）；未安装时跳过并给出提示。

### 批量

```bash
node tools/keygen.cjs batch --in orders.csv --out licenses.csv
```

`orders.csv` 使用**数值 featMask**，避免 `run,schedule,export` 里的逗号破坏 CSV：

```csv
deviceCode,expire,featMask,licenseId
S8RK-03VF-8RFR-5BNB,2026-12-31,7,10001
3BX6-570P-TMJ0-CVK7,perpetual,1,10002
```

- 首行表头；允许 UTF-8 BOM 与 CRLF。
- `expire` 为 `YYYY-MM-DD` 或 `perpetual`。
- `featMask` 为 0～255 的整数。
- `licenseId` 可留空，由 `--start-id` 自动分配；非空时必须唯一。
- 设备码按第 3 节规范化；非法行整行报错并继续处理下一行，最后汇总失败行号。

### 核心实现（与第 3、4 节严格一致）

```js
const PKG = 'com.forest.android.assistant';

// 发证端只需要设备码，不需要客户的 ANDROID_ID
const canonicalDeviceCode = normalizeDeviceCode(deviceCodeInput); // 16 字符，无连字符
const deviceTag = sha256('TAG1|' + canonicalDeviceCode).subarray(0, 10);

const formatVersion = 1;
const ver = ((keyId & 0x0f) << 4) | (formatVersion & 0x0f);

const header = Buffer.alloc(18);
header[0] = ver;
header[1] = featMask & 0xff;
header.writeUInt16BE(expDay, 2);          // 0..65535；0 = 永久
header.writeUInt32BE(licenseId >>> 0, 4); // 1..0xFFFFFFFF
deviceTag.copy(header, 8);

const message = Buffer.concat([Buffer.from('LIC1', 'ascii'), header]);
const sig = crypto.sign('sha256', message, {
  key: privateKey,
  dsaEncoding: 'der',   // 显式固定 DER；不要依赖 Node 默认值
});
const license = Buffer.concat([header, sig]).toString('base64url');
```

注意：

- `licenseId` 必须校验为 `1..0xFFFFFFFF`，`featMask` 为 `0..255`，`expDay` 为 `0..65535`；越界直接报错。
- Node v24 实测默认输出 DER，但 `dsaEncoding: 'der'` 仍应显式保留，避免 Node 版本或调用方式变化。
- 若手工实现设备码，请用第 10 节向量核对；不要用带连字符的展示串直接计算 `deviceTag`。

## 6. 校验（App 端）

`License.kt` 只做纯算法，不依赖 Android；`PublicKey`、`deviceTag`、`today`、`maxSeenDay` 全部由外部注入。

```kotlin
const val HEADER_SIZE = 18
const val MIN_SIG_SIZE = 8      // DER SEQUENCE + 两个最短 INTEGER
const val MAX_SIG_SIZE = 72     // P-256 DER 上限
const val EPOCH_DAY = 18262L    // 2020-01-01 的 epoch day

fun verify(
    raw: ByteArray,
    expectedTag: ByteArray,
    keys: Map<Int, PublicKey>,   // keyId -> 公钥
    today: Long,
    maxSeenDay: Long
): Result {
    if (raw.size !in (HEADER_SIZE + MIN_SIG_SIZE)..(HEADER_SIZE + MAX_SIG_SIZE)) {
        return Result.Invalid(Reason.BAD_FORMAT)
    }

    val ver = raw[0].toInt() and 0xFF
    val formatVersion = ver and 0x0F
    val keyId = ver ushr 4
    if (formatVersion != 1) return Result.Invalid(Reason.BAD_VERSION)
    val key = keys[keyId] ?: return Result.Invalid(Reason.UNKNOWN_KEY)

    val header = raw.copyOfRange(0, HEADER_SIZE)

    // ① 设备绑定：先比设备标签，省一次验签开销
    if (!MessageDigest.isEqual(header.copyOfRange(8, 18), expectedTag)) {
        return Result.Invalid(Reason.DEVICE_MISMATCH)
    }

    // ② 验签：损坏的 DER 可能抛 SignatureException，必须转为 BAD_SIGNATURE
    val ok = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update("LIC1".toByteArray(Charsets.US_ASCII))
            update(header)
            verify(raw, HEADER_SIZE, raw.size - HEADER_SIZE)
        }
    } catch (e: GeneralSecurityException) {
        false
    }
    if (!ok) return Result.Invalid(Reason.BAD_SIGNATURE)

    // ③ 先解析 header：licenseId=0 属于格式错误，不能被时钟异常掩盖
    val expDay = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
    val licenseId = ((header[4].toLong() and 0xFF) shl 24) or
                    ((header[5].toLong() and 0xFF) shl 16) or
                    ((header[6].toLong() and 0xFF) shl 8) or
                    (header[7].toLong() and 0xFF)
    if (licenseId == 0L) return Result.Invalid(Reason.BAD_FORMAT)

    // ④ 时间：异常时把 licenseId/expDay 带回去，导入流程据此判断能否恢复
    if (today < 0) return Result.ClockAnomaly(licenseId, expDay, maxSeenDay)
    if (today < maxSeenDay - 5) return Result.ClockAnomaly(licenseId, expDay, maxSeenDay)

    val nextMaxSeenDay = maxOf(maxSeenDay, today)
    if (expDay != 0 && nextMaxSeenDay > expDay) {
        return Result.Expired(expDay, nextMaxSeenDay)
    }

    val feat = header[1].toInt() and 0xFF
    return Result.Active(feat, expDay, licenseId, nextMaxSeenDay)
}
```

`ClockAnomaly` 携带 `licenseId` / `expDay` / 当前 `maxSeenDay`：普通启动路径遇到它一律拒绝运行；只有导入流程在第 9 节恢复条件全部满足时，才允许重置 `maxSeenDay`。`Expired` 也携带 `nextMaxSeenDay`，调用方必须把它持久化，否则“过期后回拨一天”会重新变成 `ACTIVE`。验签通过后（包括 `Expired` / `ClockAnomaly` 分支）都必须更新 `maxLicenseId`，否则旧证可以被反复用来恢复时钟。

当前 UTC 天数：

```kotlin
val today = System.currentTimeMillis() / 86_400_000L - EPOCH_DAY
if (today < 0) return Result.ClockAnomaly(...)   // 设备时间早于 2020-01-01
```

**不要用 `LocalDate` / `Calendar`**，避免设备时区与夏令时影响。验签约 1ms，可以每次启动、回到前台、运行前都重算，不要只信任缓存。

### 校验顺序的理由

1. 先做长度、格式版本、keyId 检查：这些不需要验签，且能给出“版本过新 / 公钥未知”的明确提示。
2. 再比 `deviceTag`：设备不符时省掉一次 ECDSA 验签，错误信息也更精确（“许可证不属于本机” vs “许可证无效”），便于售后判断是发错证还是文件损坏。
3. 最后验签、判时间。`deviceTag` 比较**不是安全边界**，它只是未认证的提示；真正的认证来自签名。

### 状态与错误码

| 状态 | 含义 | 是否允许运行 |
| --- | --- | --- |
| `UNBOUND` | 尚未导入许可证，或设备标识不可用 | 否 |
| `ACTIVE` | 验签通过、设备匹配、未到期 | 按功能位 |
| `EXPIRED` | 验签通过，但 `nextMaxSeenDay > expDay` | 否 |
| `INVALID` | 格式、版本、公钥、设备或签名错误 | 否 |
| `CLOCK_ANOMALY` | 设备时间明显回拨或早于 2020-01-01 | 否 |
| `STORAGE_ERROR` | 认证状态无法持久化（`commit()` 失败） | 否 |

`INVALID` 的原因至少包括：`BAD_FORMAT`、`BAD_VERSION`、`UNKNOWN_KEY`、`DEVICE_MISMATCH`、`BAD_SIGNATURE`、`DEVICE_CODE_INVALID`。实现中不必再单列 `TOO_SHORT`，长度不足统一归入 `BAD_FORMAT`。

## 7. 录入方式与激活流程

120 字符不能手输，按优先级：

| 方式 | 体验 | 依赖 |
| --- | --- | --- |
| ① 文件导入（`license.lic`，SAF `OpenDocument`） | 最好 | 复用现有导入 ZIP 的 launcher 写法 |
| ② 粘贴（微信/短信复制） | 好 | `ClipboardManager` |
| ③ 扫码（keygen 出 QR PNG） | 最好，但需相机 | 新增 `mlkit:barcode-scanning`（约 2.5MB） |
| ④ 手输 | 不可行 | — |

首版实现 ① + ②，零新增 App 依赖；③ 作为后续增强。

### 导入流程

1. 从文件或剪贴板取得文本；文件读取上限 4 KB。
2. 去掉 BOM 与空白，严格 Base64URL 解码；失败提示“许可证文件损坏或不是 .lic”。
3. 用本机 `DeviceIdentity` 得到的 `deviceTag` 和内置公钥验签；失败按第 6 节错误码提示。
4. **验证失败绝不覆盖现有许可证**；保留旧证并显示错误。
5. 验证通过后写入 `LicenseStore`，刷新 `DeviceAuth` 状态。任何验签通过且设备匹配的导入（包括已过期证）都要更新 `maxLicenseId = max(maxLicenseId, licenseId)`；只有第 9 节的时钟异常恢复条件才允许重置 `maxSeenDay`。
6. 激活成功后：
   - 若具备 `RUN` 功能位，重新显示悬浮球（或等下一次 `onResume` 自动显示）；
   - 若具备 `SCHEDULE` 功能位，遍历 `ScheduleManager.load().filter { it.enabled }` 重新 `enqueue`，确保之前被跳过的周期任务恢复；
   - 刷新“权限”页与任务页的锁定状态；
   - 日志只记录 `licenseId`、到期日、功能位，**不要记录完整许可证或原始 `ANDROID_ID`**。

若新证验签通过但已过期，而当前已有 `ACTIVE` 证，应二次确认后再覆盖；默认保留仍有效的旧证。

### 错误文案建议

| 原因 | 文案 |
| --- | --- |
| `BAD_FORMAT` | 许可证文件损坏或格式不正确 |
| `BAD_VERSION` | 许可证格式版本不受支持，请升级 App |
| `UNKNOWN_KEY` | 许可证使用了当前版本不认识的密钥，请升级 App |
| `DEVICE_MISMATCH` | 许可证不属于本机（设备码可能因恢复出厂、换签名或换机而变化） |
| `BAD_SIGNATURE` | 许可证无效或已被修改 |
| `EXPIRED` | 许可证已于 yyyy-MM-dd 到期 |
| `CLOCK_ANOMALY` | 系统时间异常，请校准时间或联系售后 |
| `STORAGE_ERROR` | 无法保存许可证，请检查存储空间后重试 |
| `DEVICE_UNAVAILABLE` | 无法读取设备标识，请联系售后 |

## 8. 续期、撤销与公钥轮换

### 续期

每次续期都重签一张新许可证：

- `deviceTag` 不变；
- `expDay` 更大；
- **`licenseId` 必须是新的唯一值**；
- 客户覆盖导入即可。

不要复用 `licenseId`。撤销表按 ID 撤销，复用会让“撤销旧证”同时撤销续期证。

### 撤销（首版不做）

首版是纯离线授权，没有 `INTERNET` 权限，也不实现撤销。若后续要做，必须先满足：

- 新增 `INTERNET` 权限与网络栈；
- 撤销表使用独立域分隔（`"REV1"`）和签名；签名密钥最好是独立密钥，至少不能依赖已泄漏的旧密钥；
- 载荷包含 `ver`、`issuedAt`、`nextUpdate`、`keyId`、`revoked: [licenseId...]`；
- App 先验签，再检查 `issuedAt` / `nextUpdate` 与本地 `maxSeenDay`，拒绝比缓存更旧的表，防止重放；
- 拉取失败或表过期时的策略必须明确：fail-open（继续用旧证）还是 fail-closed（阻止运行）；建议默认 fail-open，但记录状态；
- 离线设备永远看不到新表，因此**永久许可证在离线设备上无法被及时撤销**。

“永久许可证 = 放弃撤销能力”只在**长期离线**的意义上成立；联网设备仍可撤销。建议默认 1 年期，续期免费。

### 公钥轮换

`ver` 的高 4 位是 `keyId`，APK 内置 `keyId -> 公钥` 映射：

```
keyId 0 -> assets/license_pub.pem       （当前）
keyId 1 -> assets/license_pub_1.pem     （下一把）
```

轮换顺序：

1. 发布同时内置新旧两把公钥的 APK，等待用户升级；
2. 继续用旧 keyId 签发，直到所有旧 keyId 许可证都到期或完成换证；
3. 停止旧 keyId 签发；
4. 再等一个“最长许可证期限 + 分发尾巴”，确认没有旧证仍有效，才在新 APK 移除旧公钥。

以默认 1 年期为例，第 3 步到第 4 步至少需要 12 个月，不能照搬“3 个月 + 3 个月”：第 3 个月刚签出的一年期证，在第 6 个月仍有约 9 个月有效期。

**紧急情况**（旧私钥泄漏或疑似泄漏）：

1. 立即停止旧 keyId 签发；
2. 发布只带新公钥的 APK，要求用户强制升级；
3. 用新 keyId 重发全部许可证；
4. 旧 APK 仍会接受旧证直到到期；离线设备无法远程撤销，这一点必须接受。

私钥丢失无法用旧公钥签发新证，只能发新公钥 APK 并重发全部许可证。APK 签名密钥丢失更严重：无法覆盖升级，且所有设备码会变化；必须离线备份 keystore。

## 9. 状态机、存储与时间

```
UNBOUND ──导入许可证──▶ 校验 ─┬─ 通过 ─────────────▶ ACTIVE(feat, expDay)
                              ├─ 设备不符 ─────────▶ INVALID(DEVICE_MISMATCH)
                              ├─ 签名错 ───────────▶ INVALID(BAD_SIGNATURE)
                              ├─ 格式/版本/密钥错 ─▶ INVALID(...)
                              ├─ 时间异常 ─────────▶ CLOCK_ANOMALY
                              └─ 过期 ─────────────▶ EXPIRED(expDay)
```

存储使用 `SharedPreferences("device_auth")`，写入必须用 `commit()` 并检查返回值；不要用 `apply()` 保存 `max_seen_day`。

| key | 内容 |
| --- | --- |
| `license` | 许可证原文（去 BOM/空白后的规范串） |
| `license_sha` | 原文 SHA-256，仅用于检测本地存储损坏，不提供防篡改保证 |
| `max_seen_day` | 见过的最大 UTC 天数（防回拨） |
| `max_license_id` | 见过的最大 `licenseId`；任何验签通过的导入都会推进，用于时钟异常恢复 |
| `revocation` | 最近一次撤销表 + 签名（首版不写） |

### 必须排除备份

当前 `AndroidManifest.xml` 是 `android:allowBackup="true"`。如果 `device_auth` 被 Auto Backup / 设备迁移恢复，旧的 `max_seen_day` 会一起回来，时间回拨防护会被绕过。

保留任务数据的备份能力，但排除认证偏好：

```xml
<!-- res/xml/backup_rules.xml -->
<full-backup-content>
    <exclude domain="sharedpref" path="device_auth.xml" />
</full-backup-content>

<!-- res/xml/data_extraction_rules.xml -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="device_auth.xml" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="device_auth.xml" />
    </device-transfer>
</data-extraction-rules>
```

```xml
<application
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules"
    ... />
```

如果不想保留任何备份，直接 `android:allowBackup="false"` 更简单，但用户会失去任务数据的自动备份。

### 存储损坏与篡改检测

`license` 与 `license_sha` 可能同时被改；`license_sha` 只能发现“偏好文件被局部破坏”，不能提供安全保证。读取时：

1. 用 `license` 原文重新计算 SHA-256，与 `license_sha` 比较；不一致判 `STORAGE_ERROR`，提示重新导入，不要静默信任。
2. 无论 `license_sha` 是否一致，**每次都要重新验签**；签名才是唯一的认证依据。
3. `max_seen_day` / `max_license_id` 正常路径只能单调递增；读取到非法值（负数、非数字、超过合理上限）按 `STORAGE_ERROR` 处理。唯一允许下调 `max_seen_day` 的是第 9 节的时钟异常恢复规则。

### 时间回拨处理

```
today = utcDay()
if (today < 0)                 -> CLOCK_ANOMALY，不更新 maxSeenDay
if (today < maxSeenDay - 5)    -> CLOCK_ANOMALY，不更新 maxSeenDay
else                           -> maxSeenDay = max(maxSeenDay, today)
到期判断使用更新后的 maxSeenDay
```

要点：

- **到期判断必须用更新后的 `maxSeenDay`，不能用 `today`。** 否则“到期后把时间回拨一天”会让许可证重新变成 `ACTIVE`。
- 只要许可证通过验签和设备匹配，即使已经过期，也要推进 `maxSeenDay`（除非处于 `CLOCK_ANOMALY`），并更新 `maxLicenseId = max(maxLicenseId, licenseId)`。这样后续回拨才能被识别，旧证也不能用于重置时钟。
- 5 天容差用于覆盖少量手动校准/时钟漂移，不是因为时区切换（UTC 天数不受时区影响）。
- 持久化 `maxSeenDay` 失败时按 `CLOCK_ANOMALY` 或 `STORAGE_ERROR` 处理，fail-closed；不要让内存状态与磁盘状态不一致。

### 时钟异常的恢复

误把时间调到未来再改回来，会让 `maxSeenDay` 停在未来，持续触发 `CLOCK_ANOMALY`。恢复规则：

1. 用户校准时间后，如果 `today >= maxSeenDay - 5`，自动恢复正常。
2. 否则只允许通过**导入一张新的、验签通过的许可证**恢复，且必须同时满足：
   - `licenseId > maxLicenseId`；
   - `expDay == 0 || today <= expDay`；
   - 设备标签匹配。
3. 满足时把 `maxSeenDay` 重置为 `today`，更新 `maxLicenseId`，回到 `ACTIVE`。
4. 旧证重复导入不能重置；已过期的新证也不能重置。

这条规则把“重置单调时钟”的能力交给发证方，避免用户通过重复导入旧证绕过。售后 SOP 中要写明：遇到时钟异常，发一张 `licenseId` 更大的新证。

### 必须承认的时间边界

- **时间冻结**：用户把系统时间固定在到期前的某一天，`today` 不前进，`maxSeenDay` 也不前进，可以一直使用。纯离线方案无法防止。
- **清除应用数据 / 恢复旧备份**：`maxSeenDay` 归零；如果备份未排除，旧值也会回来。必须排除备份，并接受清数据这一边界。
- **首次运行前回拨**：如果设备在到期后从未运行过 App，直接把时间回拨到到期前，本地没有“已见过较晚日期”的记录，无法识别。
- **root / 改机**：可以直接改偏好文件，无法防。

可选加固：在同一次开机内记录 `SystemClock.elapsedRealtime()`，如果墙上时钟超过 24 小时没有前进而 `elapsedRealtime` 前进超过 24 小时，判为时钟冻结。但重启即失效，只能提高成本，不能作为强保证；首版不做。

## 10. 测试向量与测试计划

密钥对与许可证由脚本真实生成，并用 Node（签发）+ 桌面 Java（`SHA256withECDSA`，与 Android 同一套标准 API）交叉验证。

**测试公钥（仅用于单测，不是生产密钥）**

```
-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEV3g8g3C7tXQRn6McHU81j+gy3Xbo
MHMMFr8FvSdGKwR4YBIj7QM+mkfBp2R08cqG3l4s52rQh9ZI++yXuCiORQ==
-----END PUBLIC KEY-----
```

### 设备码向量

| androidId | 规范串（无连字符） | 展示串 | deviceTag（hex） |
| --- | --- | --- | --- |
| `9774d56d682e549c` | `S8RK03VF8RFR5BNB` | `S8RK-03VF-8RFR-5BNB` | `69f13bf3403385d62032` |
| `a1b2c3d4e5f60718` | `3BX6570PTMJ0CVK7` | `3BX6-570P-TMJ0-CVK7` | `adaf627205ffd9703723` |
| `0123456789abcdef` | `4530PQ4V3PQN71TR` | `4530-PQ4V-3PQN-71TR` | `6f9c88cbee6c316e5ad2` |

> `9774d56d682e549c` 是旧模拟器的经典 `ANDROID_ID`，生产逻辑会拒绝它；这里仅用于确定性测试向量。单元测试直接注入 `deviceTag`，不经过 `DeviceIdentity` 的 denylist。

### 许可证向量

| androidId | 设备码（展示） | feat | 到期 | licenseId | 字节 | 字符 | 许可证 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `9774d56d682e549c` | `S8RK-03VF-8RFR-5BNB` | 7（全功能） | 永久（`expDay=0`） | 10001 | 90 | 120 | `AQcAAAAAJxFp8TvzQDOF1iAyMEYCIQCqF5EYEsB7T-_jDZpXlf0f7t6ygLbP3fdQIm7ZyIIG0AIhALyXzPCmIeMPZsJ8tPK6f1XYb5ORaF8eJ9LZ4FUAIgI5` |
| `a1b2c3d4e5f60718` | `3BX6-570P-TMJ0-CVK7` | 1（运行） | 2026-12-31（`expDay=2556`） | 10002 | 89 | 119 | `AQEJ_AAAJxKtr2JyBf_ZcDcjMEUCIA5x74QjslngTBTn8AncIDRm0luFIhCuOpm66NpXFpgCAiEAxIApyIZ3EoJTfM66dicZLYClNIVg70H7xjw-4_2ZxuA` |
| `0123456789abcdef` | `4530-PQ4V-3PQN-71TR` | 3（运行+定时） | 2027-06-30（`expDay=2737`） | 10003 | 89 | 119 | `AQMKsQAAJxNvnIjL7mwxblrSMEUCIBO5KGtykGT9oKgqFmRZUP07FXccqiAr5__k99kfURkcAiEA-PVZgq8cAj-o7dz_H1R9jjLxb0BmS2H0Di0ZRcb9L8Y` |

### 已验证（Node + 桌面 Java）

```
androidId = 9774d56d682e549c
  own license       -> PASS feat=7 expDay=0 lid=10001
  other device      -> DEVICE_MISMATCH
  tampered license  -> BAD_SIGNATURE
  after expiry      -> PASS feat=7 expDay=0 lid=10001     ← 永久证，永不过期

androidId = a1b2c3d4e5f60718
  own license       -> PASS feat=1 expDay=2556 lid=10002
  other device      -> DEVICE_MISMATCH
  tampered license  -> BAD_SIGNATURE
  after expiry      -> EXPIRED(expDay=2556)

androidId = 0123456789abcdef
  own license       -> PASS feat=3 expDay=2737 lid=10003
  other device      -> DEVICE_MISMATCH
  tampered license  -> BAD_SIGNATURE
  after expiry      -> EXPIRED(expDay=2737)
```

签名编码实测：

- Node v24 省略 `dsaEncoding` 时默认仍是 DER，可被 Java 的 `SHA256withECDSA` 验证；仍应显式传 `'der'`。
- 3,000 次签名长度为 69～72 字节，不是固定 70～72。
- 修改 DER 首字节或截断签名时，Java 会抛 `SignatureException: Invalid encoding for signature`，不是返回 `false`；实现必须捕获并转 `BAD_SIGNATURE`。

### 输入容错实测

| 输入 | 结果 |
| --- | --- |
| `3BX6-570P-TMJ0-CVK7` | PASS |
| ` 3bx6 570p tmj0 cvk7 `（小写 + 空格） | PASS |
| `3BXG-57OP-TMJ0-CVK7`（抄错字符；`O→0` 后仍与真码不同） | DEVICE_MISMATCH |
| 许可证截断到 20 字符 | BAD_FORMAT（不足 26 字节） |
| 篡改 `ver` 字节 | BAD_VERSION |
| 设备码只有 15 位 | DEVICE_CODE_INVALID |
| 设备码混入 `/` 或中文标点 | DEVICE_CODE_INVALID |

### 待实现后必须覆盖的测试

- `LicenseTest`：三条向量、永久证、过期、篡改 header、截断、非法 DER、未知 keyId、错误 formatVersion。
- `DeviceBindTest`：规范串/展示串、规范化、大小写/空格/连字符、易混字符、长度边界、非法字符。
- `DeviceIdentityTest`：`null`、`unknown`、非 16 位、非 hex、denylist；不读取真实设备。
- `DeviceAuthTest`：`ACTIVE` / `EXPIRED` / `INVALID` / `CLOCK_ANOMALY` / `UNBOUND` 状态迁移；功能位；导入失败不覆盖旧证；新 `licenseId` 恢复时钟异常；旧证不能恢复。
- `LicenseStoreTest`：`commit()` 失败 fail-closed；`maxSeenDay` 单调；`license_sha` 损坏检测；用内存 `KeyValueStore` 替身，不依赖 Robolectric。
- Android 24 真机/模拟器：`KeyFactory("EC")`、`SHA256withECDSA`、纯 Kotlin Base64URL、PEM 资产加载、SAF 导入。

## 11. 代码落点与接入点

```
core/device/DeviceBind.kt          # 设备码 / deviceTag / 规范化（纯算法，无 Android 依赖，JVM 可测）
core/device/DeviceIdentity.kt      # ANDROID_ID 读取 + 校验 + 缓存 + 机型摘要
core/device/Base64Url.kt           # 严格 Base64URL 编解码（纯 Kotlin，API 24 可用）
core/device/License.kt             # 18 字节 header 解析 + ES256 验签 + 时间判定（纯逻辑）
core/device/LicenseKeys.kt         # PEM/SPKI 解析、keyId -> PublicKey、资产加载适配
core/device/LicenseStore.kt        # 持久化 + maxSeenDay/maxLicenseId + license_sha 自检
core/device/DeviceAuth.kt          # 状态机 + 功能位 + 导入/刷新/激活副作用
tools/keygen.cjs                   # init / issue / batch / verify（私钥从文件 + 密码读取）
app/src/main/assets/license_pub.pem
app/src/test/.../DeviceBindTest.kt
app/src/test/.../LicenseTest.kt
app/src/test/.../DeviceAuthTest.kt
app/src/test/.../LicenseStoreTest.kt
```

`License.kt` 必须与 Android 解耦（`PublicKey`、`deviceTag`、`today`、`maxSeenDay` 作为参数），`LicenseStore` 通过一个很小的 `KeyValueStore` 接口注入（Android 实现用 `SharedPreferences`，测试用内存实现），这样核心逻辑可以用普通 JVM 单测覆盖，不需要 `Robolectric`，与仓库现有 `app/src/test` 的风格一致。

`LicenseTest` 的验签测试直接使用 `SHA256withECDSA` + 第 10 节公钥；`DeviceAuthTest` 用假的 `today` / `maxSeenDay` 驱动状态机，不依赖系统时间。两条路径都不需要 `Robolectric`。

### 校验点（纵深防御）

| 位置 | 作用 | 未授权行为 |
| --- | --- | --- |
| `App.onCreate` | 最早读取 `DeviceAuth` 状态；不要跳过 `ScriptRunner.attach` / `AccessibilityGuard` 等停止与安全初始化 | 不自动显示悬浮球；不启动受控功能 |
| `MainActivity.onResume` / `autoEnableFloaty` | 现有代码每次回到前台都会 `ensureLauncher` | 无 `RUN` 时不显示悬浮球 |
| `ScriptRunner.toggleLauncher` / `ensureLauncher` | 工具页与自动恢复共用 | 返回 `false` 并写日志 |
| `ScriptRunner.runAndWait` | 手动、悬浮球、广播、定时任务都经过这里 | 写日志并返回 `false` |
| `ScheduledTaskWorker.doWork` | 定时任务入口 | 无 `SCHEDULE` 或 `RUN` 时跳过脚本 |
| `ScheduleManager.upsert` | 用户设置定时 | 无 `SCHEDULE` 时拒绝新建/启用，但允许关闭/删除 |
| `TaskZip.exportTask` / `exportAll` | 单任务导出、全部导出、分享 | 无 `EXPORT` 时返回 `null` 并写日志 |
| `MainActivity`「权限」页 | 展示状态、设备码、到期日、功能位、导入入口 | 显示导入按钮与错误文案 |
| `ScriptEditorActivity` 运行 | 走 `ScriptRunner.run`，无需单独判断 | 由 `runAndWait` 拦截 |

注意：当前 `MainActivity.onResume` 会无条件 `autoEnableFloaty()`，`App.onCreate` 也有多个副作用；只在 `App.onCreate` 加判断不够，必须覆盖 `onResume` 与悬浮球服务入口。

### 定时任务在未授权时的行为

- 周期任务（`EVERY_MINUTES`、`DAILY_AT`）：未授权时**不要返回 `Result.failure()` 永久终止排程**。记录日志并返回 `Result.success()`，保留 WorkManager 排程，续期后自然恢复。
- 一次性任务（`ONCE_AFTER_MINUTES`）：未授权时不执行脚本，记录日志并按现有逻辑 `ScheduleManager.markDone()`；用户续期后需重新设置。
- 授权恢复后，遍历 `ScheduleManager.load().filter { it.enabled }` 重新 `enqueue`，确保之前失败/被取消的排程恢复。

### 到期时的行为

- 不强制杀死正在运行的任务，避免把目标应用留在中间状态；当前任务可以跑完。
- 之后所有新运行、定时、导出入口拒绝。
- 下一次 `onResume` 收起悬浮球；`ScriptForegroundService` 随任务结束停止。
- 导入新证后按第 7 节激活流程恢复。

## 12. 安全边界与加固

| 加固项 | 成本 | 收益 | 说明 |
| --- | --- | --- | --- |
| 固定 APK 签名密钥 + 排除认证备份 | 低 | 高 | 本方案的前置条件，不是可选加固 |
| 校验点分散（启动 / UI / 运行 / 定时 / 导出） | 低 | 中 | 提高绕过成本，不能保证单点 patch 无效 |
| 功能位参与实际逻辑 | 中 | 中 | 让绕过需要改多个入口，不能保证“patch 掉会崩” |
| R8 混淆 + 字符串处理 | 低 | 低-中 | 当前 `isMinifyEnabled=false`；开启后需回归验签、资产加载与单测 |
| 验签下沉到 native（`.so`，JNI 只回结果） | 中 | 中 | 增加 JVM 层 hook 成本；native 也可被 hook |
| 公钥不存明文（分段异或还原） | 低 | 低 | 主要是提高阅读成本 |
| APK 完整性自检（签名/文件哈希） | 中 | 中 | 防简单重打包；改包者仍可 patch 掉自检 |
| 在线撤销 / 服务端授权 | 高 | 高 | 需要网络、服务端与隐私合规；首版不做 |

**必须承认的边界**：Frida 级动态 hook 永远能绕过客户端校验；本方案解决的是“**签名私钥泄漏**”这个最致命的问题，不是“绝对不可破解”。真正的护城河是服务端能力（任务模板更新、云同步、脚本市场）。

## 13. 落地检查清单

1. **固定 keystore**：Android 8+ 上 `ANDROID_ID` 与签名绑定；当前 CI 每次随机 debug key，会让同一设备的设备码每次构建都变化。固定签名前不要发证。
2. **备份 keystore 与私钥**：APK 签名密钥丢失 = 无法覆盖升级 + 所有设备码变化；许可证私钥丢失 = 只能发新公钥 APK 并重发全部许可证。两者都要离线备份。
3. **私钥保管**：离线机器 + 密码保护 PKCS#8 PEM；`private*.pem` / `tools/keys/` 加入 `.gitignore`；不进入 CI 制品。
4. **默认 1 年期**，不要默认发永久证；永久证在离线设备上无法及时撤销。
5. **每次签发新 `licenseId`**，包括续期；撤销表按 ID 撤销，禁止复用。
6. **售后 SOP**：恢复出厂 / 换机 / 多用户 / 工作资料 / 换签名 / Play App Signing 轮换 / 多开克隆 → 设备码变化 → 重新发证。
7. **坏 `ANDROID_ID` 兜底**：`null`、`unknown`、非 16 位 hex、`0000000000000000`、`9774d56d682e549c`、`ffffffffffffffff` 一律不允许激活；首版不实现试用。
8. **不阻塞主界面**：未激活时允许浏览任务、编辑脚本；只拦截运行、定时、导出与悬浮球。
9. **覆盖所有入口**：`App.onCreate`、`MainActivity.onResume`、`ScriptRunner`、`ScheduledTaskWorker`、`ScheduleManager`、`TaskZip`；不要只改 UI。
10. **单测先行**：先把第 10 节向量写成 `LicenseTest` / `DeviceBindTest`，再实现 `License.kt`，确保两端算法不漂移。
11. **显式 `dsaEncoding: 'der'`**：不要依赖 Node 默认值；Java 端捕获 `SignatureException`，非法 DER 转 `BAD_SIGNATURE`，不要让异常冒到启动流程。
12. **Base64 兼容 API 24**：不要在 App 里直接用 `java.util.Base64`（API 26）；用纯 Kotlin Base64URL 或 Android 适配层。
13. **时间回拨**：到期判断用更新后的 `maxSeenDay`，不是 `today`；验签通过后即使过期也推进 `maxSeenDay` 和 `maxLicenseId`；时钟异常只允许用更高 `licenseId` 的新证恢复。
14. **承认时间边界**：时间冻结、首次运行前回拨、清数据、root 都无法离线防；不要对外承诺“改时间一定无效”。
15. **排除认证备份**：`device_auth` 偏好必须从 Auto Backup / D2D 迁移中排除，否则旧 `max_seen_day` 会被恢复。
16. **公钥轮换**：用 `keyId` 区分；旧 keyId 至少保留“最长许可证期限 + 分发尾巴”，不能固定 3 个月；紧急轮换要强制升级并接受旧证到期前仍有效。
17. **定时任务**：未授权时周期任务返回 `success()` 保留排程，一次性任务标记完成；授权恢复后重新 `enqueue`。
18. **R8 当前关闭**：如果后续开启，回归验签、PEM 资产、Base64、Compose 与全部单测；不要假设混淆能防破解。
19. **在线撤销是后续能力**：当前 Manifest 没有 `INTERNET` 权限；要做撤销必须先设计网络、签名、重放防护与离线策略。
