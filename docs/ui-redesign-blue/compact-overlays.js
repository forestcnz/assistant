// Injected before the prototype starts. Only these five screen renderers change.
// Reference: PickerOverlay.drawReadout/onTouchEvent/onSelectionTouchEvent and
// RecordingOverlay.panelParams/hitPanel/attachDrag. No Android code is modified.
const compactPickerIds=['point','color','region','screenshot'];
const compactModel={pickers:{},recordPosition:null,gesture:null,suppressClickUntil:0};
const compactClamp=(v,min,max)=>Math.max(min,Math.min(max,v));
function nativePickerModel(mode){
  return compactModel.pickers[mode]??=(mode==='point'||mode==='color'
    ?{x:.5,y:.29}
    :{x:.24,y:.22,w:.52,h:.13,valid:true});
}
function renderNativePicker(mode){
  const area=mode==='region'||mode==='screenshot';
  return {body:`<div class="compact-stage" data-compact-picker="${mode}">
    ${sampleApp()}
    ${area?'<div class="native-selection" data-native-selection></div>':`<div class="native-crosshair" data-native-crosshair aria-hidden="true"><svg viewBox="0 0 92 92"><path d="M0 46H36 M56 46H92 M46 0V36 M46 56V92"/><circle cx="46" cy="46" r="9"/></svg></div>`}
    <button class="native-readout" data-native-confirm="${mode}" aria-label="${mode==='screenshot'?'保存所选截图':area?'复制范围代码并退出':'复制拾取结果并退出'}"></button>
    ${mode==='screenshot'?'<button class="native-picker-cancel" data-action="navigate:tools" aria-label="取消截图">取消</button>':''}
  </div>`,tab:'',footer:''};
}
const compactDescriptions={
  point:'沿用现有操作：拖动定位，轻点画面或读数条确认并复制 click() 代码。只有准星和 24 px 高的读数条；读数自动避开准星并在屏幕边缘换位。系统返回或 Esc 取消。',
  color:'冻结画面取色，拖动定位，轻点确认并复制找色代码。读数条只保留坐标、12 px 色样与 HEX，移除 RGB、大按钮和常驻说明。示例颜色固定，不访问真实屏幕。',
  region:'按现有逻辑拖动重新框选，轻点选区旁的读数条复制 region 代码并退出。保留 X/Y、W/H 两行读数；不增加独立工具栏或角点编辑交互。系统返回或 Esc 取消。',
  screenshot:'拖动框选，轻点读数条中的「保存」交给系统文件选择器。只在右上角保留 48 × 36 px 取消按钮；读数自动放在选区外侧，空间不足时翻到上方。',
  recording:'沿用现有约 144 × 34 dp 单行悬浮条，默认停靠左下角。拖动状态和步数区域移动；暂停／开始／继续、完成始终可见。操作说明放在稿外，画面内直接点击或滑动模拟录制。'
};
for(const id of compactPickerIds){getPage(id).render=()=>renderNativePicker(id);getPage(id).desc=compactDescriptions[id];}
getPage('recording').desc=compactDescriptions.recording;
getPage('recording').render=()=>{
  const count=state.recordSteps??8;
  const phase=state.recordPaused?(count?'暂停':'就绪'):'录制';
  const toggleText=state.recordPaused?(count?'继续':'开始'):'暂停';
  return {body:`<div class="compact-stage" data-compact-recording>
    ${sampleApp()}
    <div class="native-record-bar" data-native-record-bar data-paused="${state.recordPaused}">
      <div class="native-record-handle" data-native-record-handle title="拖动悬浮条" aria-label="${phase} ${count} 步，拖动可移动悬浮条"><i class="native-record-dot"></i><span class="native-record-label">${phase} ${count}步</span></div>
      <button class="native-record-button" data-action="pauseRecord" aria-label="${toggleText}录制">${toggleText}</button>
      <button class="native-record-button finish" data-go="record-save" aria-label="完成录制">完成</button>
    </div>
  </div>`,tab:'',footer:''};
};

function nativeScreenCoords(stage,model){
  // Example device: 1080 × 2400. Include the status-bar offset in screen Y,
  // matching the native getLocationOnScreen correction.
  const phone=stage.closest('.phone');
  const screenHeight=phone.clientHeight;
  const statusHeight=phone.querySelector('.statusbar').offsetHeight;
  const x=Math.round(model.x*1080);
  const y=Math.round((statusHeight+model.y*stage.clientHeight)/screenHeight*2400);
  return {x,y,w:Math.max(1,Math.round((model.w||0)*1080)),h:Math.max(1,Math.round((model.h||0)*stage.clientHeight/screenHeight*2400))};
}
function updateNativePicker(stage){
  const mode=stage.dataset.compactPicker,model=nativePickerModel(mode);
  const box=stage.querySelector('[data-native-selection]');
  const cross=stage.querySelector('[data-native-crosshair]');
  const chip=stage.querySelector('[data-native-confirm]');
  const home=stage.closest('.phone').querySelector('.home-indicator');
  if(!home.querySelector('.native-system-back'))home.insertAdjacentHTML('beforeend',`<button class="native-system-back" data-action="navigate:tools" aria-label="系统返回，取消当前工具">${icon('back')}</button>`);
  const width=stage.clientWidth,height=stage.clientHeight;
  if(!width||!height)return;
  let cx,top,bottom;
  const p=nativeScreenCoords(stage,model);
  if(box){
    box.hidden=!model.valid;
    box.style.left=model.x*width+'px';box.style.top=model.y*height+'px';
    box.style.width=model.w*width+'px';box.style.height=model.h*height+'px';
    cx=(model.valid?model.x+model.w/2:.5)*width;
    top=(model.valid?model.y:.5)*height;bottom=(model.valid?model.y+model.h:.5)*height;
    chip.innerHTML=model.valid?`<span>X ${p.x}  Y ${p.y}</span><span>W ${p.w}  H ${p.h}${mode==='screenshot'?' <b class="native-inline-action">· 保存</b>':''}</span>`:`<span>${mode==='screenshot'?'拖动框选截图区域':'拖动框选范围'}</span>`;
    chip.setAttribute('aria-disabled',String(!model.valid));
  }else{
    cx=model.x*width;top=model.y*height-46;bottom=model.y*height+46;
    cross.style.left=cx+'px';cross.style.top=model.y*height+'px';
    chip.innerHTML=`<span>X ${p.x}  Y ${p.y}</span>${mode==='color'?'<span class="native-color-line"><i class="native-color-dot"></i>#588C68</span>':''}`;
  }
  const minTop=mode==='screenshot'?52:6;
  const chipTop=bottom+6+chip.offsetHeight<=height-6?bottom+6:top-6-chip.offsetHeight;
  chip.style.left=compactClamp(cx-chip.offsetWidth/2,6,width-chip.offsetWidth-6)+'px';
  chip.style.top=compactClamp(chipTop,minTop,height-chip.offsetHeight-6)+'px';
}
function positionNativeRecorder(stage){
  const panel=stage.querySelector('[data-native-record-bar]');
  if(!compactModel.recordPosition)return;
  panel.style.left=compactClamp(compactModel.recordPosition.x*stage.clientWidth,0,stage.clientWidth-panel.offsetWidth)+'px';
  panel.style.top=compactClamp(compactModel.recordPosition.y*stage.clientHeight,0,stage.clientHeight-panel.offsetHeight)+'px';
  panel.style.bottom='auto';
}
function layoutCompactOverlays(){
  document.querySelectorAll('[data-compact-picker]').forEach(updateNativePicker);
  document.querySelectorAll('[data-compact-recording]').forEach(positionNativeRecorder);
}
let compactLayoutFrame=0;
function scheduleCompactLayout(){cancelAnimationFrame(compactLayoutFrame);compactLayoutFrame=requestAnimationFrame(layoutCompactOverlays);}
const compactObserver=new MutationObserver(records=>{
  // Ignore our own readout text updates. Only a newly rendered page needs layout.
  if(records.some(record=>[...record.addedNodes].some(node=>node.nodeType===1&&(node.matches('[data-compact-picker],[data-compact-recording]')||node.querySelector('[data-compact-picker],[data-compact-recording]')))))scheduleCompactLayout();
});
compactObserver.observe(root,{childList:true,subtree:true});
window.addEventListener('resize',scheduleCompactLayout);

function nativeLocalPoint(event,stage){
  const rect=stage.getBoundingClientRect();
  return {x:compactClamp((event.clientX-rect.left)/rect.width,0,1),y:compactClamp((event.clientY-rect.top)/rect.height,0,1)};
}
function nativePickerConfirm(stage){
  const mode=stage.dataset.compactPicker,model=nativePickerModel(mode);
  if((mode==='region'||mode==='screenshot')&&!model.valid)return;
  const p=nativeScreenCoords(stage,model);
  if(mode==='screenshot'){state.lastScreenshotRegion=p;handle('saveScreenshot');return;}
  const code=mode==='point'?`click(${p.x}, ${p.y});`:mode==='color'?`images.findColor(captureScreen(), "#588C68", { threshold: 16 });   // (${p.x}, ${p.y})`:`var region = [${p.x}, ${p.y}, ${p.w}, ${p.h}];`;
  state.lastPickerResult=code;
  navigate('tools');copy(code);
}

// Capture these five prototypes before the older, generic canvas listeners.
root.addEventListener('pointerdown',event=>{
  if(state.view!=='focus'||state.modal||event.button>0)return;
  const stage=event.target.closest('#live-device [data-compact-picker],#live-device [data-compact-recording]');
  if(!stage)return;
  if(event.target.closest('.native-picker-cancel'))return;
  const record=stage.hasAttribute('data-compact-recording');
  const panel=event.target.closest('[data-native-record-bar]');
  if(record&&panel&&!event.target.closest('[data-native-record-handle]'))return;
  const pos=nativeLocalPoint(event,stage);
  const model=record?null:nativePickerModel(stage.dataset.compactPicker);
  compactModel.gesture={stage,record,panel,start:pos,downX:event.clientX,downY:event.clientY,moved:false,
    readout:!!event.target.closest('[data-native-confirm]'),original:model?{...model}:null,
    panelX:panel?.offsetLeft||0,panelY:panel?.offsetTop||0};
  if(model&&'w' in model&&!compactModel.gesture.readout){Object.assign(model,{x:pos.x,y:pos.y,w:0,h:0,valid:false});updateNativePicker(stage);}
  stage.setPointerCapture(event.pointerId);
  event.preventDefault();event.stopImmediatePropagation();
},true);
root.addEventListener('pointermove',event=>{
  const drag=compactModel.gesture;if(!drag)return;
  const pos=nativeLocalPoint(event,drag.stage);
  if(Math.abs(event.clientX-drag.downX)>6||Math.abs(event.clientY-drag.downY)>6)drag.moved=true;
  if(drag.record){
    if(drag.panel&&drag.moved){
      const rect=drag.stage.getBoundingClientRect();
      const x=compactClamp(drag.panelX+(event.clientX-drag.downX)*drag.stage.clientWidth/rect.width,0,drag.stage.clientWidth-drag.panel.offsetWidth);
      const y=compactClamp(drag.panelY+(event.clientY-drag.downY)*drag.stage.clientHeight/rect.height,0,drag.stage.clientHeight-drag.panel.offsetHeight);
      compactModel.recordPosition={x:x/drag.stage.clientWidth,y:y/drag.stage.clientHeight};
      positionNativeRecorder(drag.stage);drag.panel.classList.add('is-dragging');
    }
  }else if(drag.moved){
    const model=nativePickerModel(drag.stage.dataset.compactPicker);
    if('w' in model){
      Object.assign(model,{x:Math.min(pos.x,drag.start.x),y:Math.min(pos.y,drag.start.y),w:Math.abs(pos.x-drag.start.x),h:Math.abs(pos.y-drag.start.y)});
      const p=nativeScreenCoords(drag.stage,model);model.valid=model.w>0&&model.h>0&&p.w>=(drag.stage.dataset.compactPicker==='screenshot'?2:1)&&p.h>=(drag.stage.dataset.compactPicker==='screenshot'?2:1);
    }else Object.assign(model,pos);
    updateNativePicker(drag.stage);
  }
  event.preventDefault();event.stopImmediatePropagation();
},true);
root.addEventListener('pointerup',event=>{
  const drag=compactModel.gesture;if(!drag)return;
  compactModel.gesture=null;compactModel.suppressClickUntil=performance.now()+300;
  if(drag.stage.hasPointerCapture(event.pointerId))drag.stage.releasePointerCapture(event.pointerId);
  if(drag.record){
    drag.panel?.classList.remove('is-dragging');
    // Moving the overlay and clicking its buttons must never add a recorded step.
    if(!drag.panel&&!state.recordPaused){state.recordSteps=(state.recordSteps??8)+1;refreshPhone();}
  }else{
    const model=nativePickerModel(drag.stage.dataset.compactPicker);
    if(!('w' in model)){
      if(!drag.readout||drag.moved)Object.assign(model,nativeLocalPoint(event,drag.stage));
      updateNativePicker(drag.stage);
      if(!drag.moved)nativePickerConfirm(drag.stage);
    }else if(drag.readout&&!drag.moved)nativePickerConfirm(drag.stage);
  }
  event.preventDefault();event.stopImmediatePropagation();
},true);
root.addEventListener('pointercancel',event=>{
  const drag=compactModel.gesture;if(!drag)return;
  if(drag.original){Object.assign(nativePickerModel(drag.stage.dataset.compactPicker),drag.original);updateNativePicker(drag.stage);}
  drag.panel?.classList.remove('is-dragging');compactModel.gesture=null;
  event.stopImmediatePropagation();
},true);
root.addEventListener('click',event=>{
  const stage=event.target.closest('#live-device [data-compact-picker],#live-device [data-compact-recording]');
  if(!stage)return;
  if(performance.now()<compactModel.suppressClickUntil){event.preventDefault();event.stopImmediatePropagation();return;}
  if(event.target.closest('[data-native-confirm]')){nativePickerConfirm(stage);event.preventDefault();event.stopImmediatePropagation();}
},true);
document.addEventListener('keydown',event=>{
  if(event.key==='Escape'&&state.view==='focus'&&!state.modal&&compactPickerIds.includes(state.page)){
    event.preventDefault();event.stopImmediatePropagation();navigate('tools');
  }
},true);
