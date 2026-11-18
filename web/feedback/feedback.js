(() => {
  'use strict';

  const MiB = 1024 * 1024;
  const limits = { imageCount: 3, imageBytes: 8 * MiB, videoCount: 1, videoBytes: 30 * MiB, videoSeconds: 60, totalBytes: 60 * MiB };
  const acceptedTypes = {
    jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp',
    mp4: 'video/mp4', webm: 'video/webm'
  };
  const form = document.getElementById('feedback-form');
  const fields = document.getElementById('feedback-fields');
  const imageInput = document.getElementById('image-input');
  const videoInput = document.getElementById('video-input');
  const imageButton = document.getElementById('choose-images');
  const videoButton = document.getElementById('choose-video');
  const list = document.getElementById('attachment-list');
  const attachmentStatus = document.getElementById('attachment-status');
  const submitStatus = document.getElementById('submit-status');
  const submitButton = document.getElementById('submit-feedback');
  const appName = document.getElementById('app-name');
  const versionInput = document.getElementById('xadskip-version');
  const versionPattern = /^\d{1,3}\.\d{1,3}\.\d{1,3}(?:[-+][0-9A-Za-z.-]{1,20})?$/;
  const linkedVersion = new URL(window.location.href).searchParams.get('xadskipVersion');
  if (linkedVersion && linkedVersion.length <= 40 && versionPattern.test(linkedVersion)) {
    versionInput.value = linkedVersion;
    versionInput.defaultValue = linkedVersion;
  }
  const attachments = [];
  let reading = false;
  let sending = false;
  let completed = false;
  let requestId = null;

  function makeRequestId() {
    if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }

  form.addEventListener('input', event => {
    if (event.target.type !== 'file') requestId = null;
  });

  function setStatus(element, message, error = false) {
    element.textContent = message;
    element.classList.toggle('is-error', error);
    element.setAttribute('role', error ? 'alert' : 'status');
  }

  function updateButtons() {
    imageButton.disabled = reading || attachments.filter(item => item.kind === 'image').length >= limits.imageCount;
    videoButton.disabled = reading || attachments.some(item => item.kind === 'video');
    submitButton.disabled = reading;
  }

  function displaySize(bytes) {
    return bytes < MiB ? `${Math.max(1, Math.ceil(bytes / 1024))} KB` : `${(bytes / MiB).toFixed(1)} MB`;
  }

  function removeAttachment(item) {
    if (sending) return;
    const index = attachments.indexOf(item);
    if (index < 0) return;
    attachments.splice(index, 1);
    requestId = null;
    URL.revokeObjectURL(item.url);
    renderAttachments();
    setStatus(attachmentStatus, `已移除“${item.file.name}”。`);
    (item.kind === 'image' ? imageButton : videoButton).focus();
  }

  function renderAttachments() {
    list.replaceChildren();
    attachments.forEach(item => {
      const card = document.createElement('li');
      card.className = 'attachment-card';
      const media = document.createElement(item.kind === 'image' ? 'img' : 'video');
      media.className = 'attachment-preview';
      media.src = item.url;
      if (item.kind === 'image') {
        media.alt = `截图预览：${item.file.name}`;
      } else {
        media.controls = true;
        media.preload = 'metadata';
        media.playsInline = true;
        media.setAttribute('aria-label', `录屏预览：${item.file.name}`);
      }
      const name = document.createElement('p');
      name.className = 'attachment-name';
      name.textContent = item.file.name;
      const size = document.createElement('p');
      size.className = 'attachment-size';
      size.textContent = `${displaySize(item.file.size)}${item.kind === 'video' ? ` · ${Math.ceil(item.duration)} 秒` : ''}`;
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'remove-file';
      remove.textContent = '移除';
      remove.setAttribute('aria-label', `移除${item.kind === 'image' ? '截图' : '录屏'}：${item.file.name}`);
      remove.addEventListener('click', () => removeAttachment(item));
      card.append(media, name, size, remove);
      list.append(card);
    });
    updateButtons();
  }

  function inspectMedia(url, kind) {
    return new Promise((resolve, reject) => {
      const media = document.createElement(kind === 'image' ? 'img' : 'video');
      const timer = window.setTimeout(() => finish(new Error('读取附件超时，请重新选择，或换一张截图。')), 15000);
      const cleanup = () => {
        window.clearTimeout(timer);
        media.onload = media.onerror = media.onloadedmetadata = null;
        media.removeAttribute('src');
        if (kind === 'video') media.load();
      };
      const finish = (error, value) => {
        cleanup();
        if (error) reject(error); else resolve(value);
      };
      media.onerror = () => finish(new Error(kind === 'image' ? '这张图片无法读取，请重新保存为 JPG、PNG 或 WebP。' : '这段录屏无法读取，请改用 MP4，或上传截图。'));
      if (kind === 'image') {
        media.onload = () => finish(null, 0);
      } else {
        media.preload = 'metadata';
        media.onloadedmetadata = () => {
          const duration = media.duration;
          if (!Number.isFinite(duration) || duration <= 0) {
            finish(new Error('无法确认这段录屏的时长，请剪辑后重新保存，或上传截图。'));
          } else if (duration > limits.videoSeconds) {
            finish(new Error('录屏超过 60 秒，请先剪短再添加。'));
          } else {
            finish(null, duration);
          }
        };
      }
      media.src = url;
    });
  }

  async function addFiles(fileList, kind) {
    if (reading || sending || !fileList.length) return;
    reading = true;
    updateButtons();
    setStatus(attachmentStatus, '正在准备附件，请稍等…');
    const errors = [];
    let added = 0;
    try {
      for (const file of fileList) {
        const type = acceptedTypes[file.name.split('.').pop().toLowerCase()];
        const limit = kind === 'image' ? limits.imageBytes : limits.videoBytes;
        const maxCount = kind === 'image' ? limits.imageCount : limits.videoCount;
        let reason = '';
        if (!type || !type.startsWith(`${kind}/`) || (file.type && file.type !== type)) reason = kind === 'image' ? '请选择 JPG、PNG 或 WebP 图片。' : '请选择 MP4 或 WebM 录屏。';
        else if (file.size === 0) reason = '文件是空的，请重新选择。';
        else if (file.size > limit) reason = kind === 'image' ? '图片超过 8 MB，请压缩后再添加。' : '录屏超过 30 MB，请剪短或压缩后再添加。';
        else if (attachments.some(item => item.file.name === file.name && item.file.size === file.size && item.file.lastModified === file.lastModified)) reason = '这份附件已经添加过了。';
        else if (attachments.filter(item => item.kind === kind).length >= maxCount) reason = kind === 'image' ? '最多添加 3 张截图，可先移除一张。' : '最多添加 1 段录屏，可先移除已选录屏。';
        else if (attachments.reduce((sum, item) => sum + item.file.size, file.size) > limits.totalBytes) reason = '附件合计超过 60 MB，请移除或缩小部分文件。';
        if (reason) { errors.push(`${file.name}：${reason}`); continue; }
        const url = URL.createObjectURL(file);
        try {
          const duration = await inspectMedia(url, kind);
          attachments.push({ file, kind, url, duration });
          requestId = null;
          added += 1;
        } catch (error) {
          URL.revokeObjectURL(url);
          errors.push(`${file.name}：${error.message}`);
        }
      }
    } finally {
      reading = false;
      renderAttachments();
      const note = added ? `已添加 ${added} 份附件。请检查预览，确认已遮盖私密信息。` : '';
      setStatus(attachmentStatus, [note, ...errors].filter(Boolean).join('\n'), errors.length > 0);
    }
  }

  imageButton.addEventListener('click', () => imageInput.click());
  videoButton.addEventListener('click', () => videoInput.click());
  imageInput.addEventListener('change', () => {
    const files = Array.from(imageInput.files || []);
    imageInput.value = '';
    void addFiles(files, 'image');
  });
  videoInput.addEventListener('change', () => {
    const files = Array.from(videoInput.files || []);
    videoInput.value = '';
    void addFiles(files, 'video');
  });
  appName.addEventListener('input', () => appName.setCustomValidity(''));
  versionInput.addEventListener('input', () => versionInput.setCustomValidity(''));

  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (sending || reading || completed) return;
    appName.setCustomValidity(appName.value.trim() ? '' : '请填写发生问题的应用名称。');
    const version = versionInput.value.trim();
    versionInput.setCustomValidity(!version || versionPattern.test(version) ? '' : '请填写类似 0.3.4 的版本号，不知道可以留空。');
    if (!form.reportValidity()) return;
    if (!attachments.length) {
      setStatus(attachmentStatus, '请先添加至少一张截图，或一段短录屏。', true);
      imageButton.focus();
      return;
    }
    requestId ??= makeRequestId();
    const data = new FormData(form);
    data.set('requestId', requestId);
    for (const name of ['appName', 'appVersion', 'xadskipVersion', 'description', 'email']) {
      data.set(name, String(data.get(name) || '').trim());
    }
    attachments.forEach(item => data.append('attachments', item.file, item.file.name));
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 180000);
    sending = true;
    fields.disabled = true;
    form.setAttribute('aria-busy', 'true');
    submitButton.textContent = '正在发送，请稍等…';
    setStatus(submitStatus, '正在上传反馈和附件。录屏较大时可能需要一点时间，请不要关闭页面。');
    try {
      const response = await fetch('/api/feedback', {
        method: 'POST', body: data, credentials: 'same-origin', redirect: 'error', signal: controller.signal,
        headers: { Accept: 'application/json' }
      });
      if (!response.ok) {
        const messages = {
          400: '填写内容或附件未通过检查，请检查文件格式和大小后再试。',
          413: '附件超过服务器接收上限，请缩小文件后再试。',
          415: '服务器无法接收这种附件格式，请改用 JPG、PNG 图片或 MP4 录屏。',
          422: '填写内容或附件未通过检查，请检查后再试。',
          429: '发送得有些频繁，请稍后再试。'
        };
        throw new Error(messages[response.status] || '暂时无法发送，请稍后再试。');
      }
      let result;
      try { result = await response.json(); } catch { throw new Error('未收到有效的反馈编号，暂时无法确认提交结果。'); }
      if (!result || result.ok !== true || typeof result.feedbackId !== 'string' || !result.feedbackId.trim() || result.feedbackId.length > 200 || /[\u0000-\u001f\u007f]/.test(result.feedbackId)) {
        throw new Error('未收到有效的反馈编号，暂时无法确认提交结果。');
      }
      completed = true;
      document.getElementById('feedback-id').textContent = result.feedbackId;
      requestId = null;
      document.getElementById('form-panel').hidden = true;
      document.getElementById('success-panel').hidden = false;
      document.getElementById('success-title').focus();
      attachments.forEach(item => URL.revokeObjectURL(item.url));
      attachments.length = 0;
      list.replaceChildren();
      form.reset();
    } catch (error) {
      const reason = error.name === 'AbortError' ? '等待时间较长，暂时无法确认提交结果。' : error instanceof TypeError ? '网络连接失败，暂时无法确认提交结果。' : error.message;
      setStatus(submitStatus, `${reason}\n填写内容和附件都已保留在当前页面，你可以稍后重试。`, true);
      submitStatus.focus();
    } finally {
      window.clearTimeout(timeout);
      sending = false;
      fields.disabled = false;
      form.removeAttribute('aria-busy');
      submitButton.textContent = '发送反馈 ↗';
      updateButtons();
    }
  });

  document.getElementById('send-another').addEventListener('click', () => {
    completed = false;
    setStatus(attachmentStatus, '');
    setStatus(submitStatus, '');
    document.getElementById('success-panel').hidden = true;
    document.getElementById('form-panel').hidden = false;
    form.querySelector('input[name="issueType"]').focus();
  });
  window.addEventListener('beforeunload', event => {
    const hasText = ['app-name', 'app-version', 'xadskip-version', 'description', 'email'].some(id => {
      const input = document.getElementById(id);
      return input.value.trim() !== input.defaultValue.trim();
    });
    if (!completed && (sending || reading || attachments.length || hasText)) {
      event.preventDefault();
      event.returnValue = '';
    }
  });
  fields.disabled = false;
  updateButtons();
})();
