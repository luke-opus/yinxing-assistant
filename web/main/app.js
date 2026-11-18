'use strict';
const downloadButton=document.getElementById('download-apk');
const statusText=document.getElementById('download-status');
const recipientOptions=document.querySelectorAll('input[name="download-recipient"]');
const downloadConsent=document.getElementById('download-consent');
let downloadInProgress=false;
const releaseReady=document.body.dataset.releaseReady==='true';

function selectedRecipient(){
  return document.querySelector('input[name="download-recipient"]:checked')?.value;
}
function canUseDownloadEntry(){
  const recipient=selectedRecipient();
  return releaseReady&&(recipient==='elder'||recipient==='caregiver')&&downloadConsent?.checked===true;
}
function updateDownloadState(updateStatus=true){
  const eligible=canUseDownloadEntry();
  downloadButton.disabled=downloadInProgress||!eligible;
  if(!updateStatus)return;
  if(!releaseReady){
    statusText.textContent='源码模板预览，安装包尚未准备好，暂不能下载。';
  }else if(selectedRecipient()==='other'){
    statusText.textContent='本下载入口服务于长者及协助安装的家属。你仍可查看使用说明与开源资料。';
  }else if(eligible){
    statusText.textContent='已确认使用对象和说明，可以下载安装包。';
  }else{
    statusText.textContent='请选择使用对象，并确认已阅读使用说明。';
  }
}
recipientOptions.forEach(option=>option.addEventListener('change',()=>updateDownloadState()));
downloadConsent?.addEventListener('change',()=>updateDownloadState());
updateDownloadState();

downloadButton.addEventListener('click',()=>{
  if(downloadInProgress)return;
  if(!canUseDownloadEntry()){
    updateDownloadState();
    return;
  }
  downloadInProgress=true;
  downloadButton.disabled=true;
  try{
    // Deployment verifies the full APK against its manifest before publishing.
    // Hand the same-origin file to the browser without buffering it in page memory.
    const link=document.createElement('a');
    link.href='downloads/XAdSkip-0.3.4.apk';
    link.download='XAdSkip-0.3.4.apk';
    link.hidden=true;
    document.body.append(link);
    link.click();
    link.remove();
    statusText.textContent='已交给浏览器下载，请查看下载记录。';
  }catch(error){statusText.textContent='未能启动下载，请再试一次。';}
  finally{
    setTimeout(()=>{
      downloadInProgress=false;
      updateDownloadState(false);
    },750);
  }
});

function revealPolicyDetails(hash){
  if(hash!=='#privacy'&&hash!=='#licenses')return;
  const details=document.getElementById(hash.slice(1));
  if(details?.tagName==='DETAILS')details.open=true;
}
// Open before native fragment navigation, including a repeated click on the same hash.
document.querySelectorAll('a[href="#privacy"],a[href="#licenses"]').forEach(link=>{
  link.addEventListener('click',()=>revealPolicyDetails(link.getAttribute('href')));
});
window.addEventListener('hashchange',()=>revealPolicyDetails(window.location.hash));
revealPolicyDetails(window.location.hash);

// Content stays visible without JavaScript; motion is decorative and runs once.
if ('IntersectionObserver' in window && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        entry.target.classList.add('enter-view');
        observer.unobserve(entry.target);
      }
    }
  }, {threshold:0.12});
  document.querySelectorAll('.section-heading,.steps article,.rules-grid article').forEach(item => observer.observe(item));
}
