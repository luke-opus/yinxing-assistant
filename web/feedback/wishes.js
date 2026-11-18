(() => {
  'use strict';

  const MiB = 1024 * 1024;
  const limits = { imageCount: 3, imageBytes: 8 * MiB, videoCount: 1, videoBytes: 30 * MiB, videoSeconds: 60, totalBytes: 60 * MiB };
  const acceptedTypes = {
    jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp',
    mp4: 'video/mp4', webm: 'video/webm'
  };
  const form = document.getElementById('wish-form');
  const fields = document.getElementById('wish-fields');
  const imageInput = document.getElementById('image-input');
  const videoInput = document.getElementById('video-input');
  const imageButton = document.getElementById('choose-images');
  const videoButton = document.getElementById('choose-video');
  const list = document.getElementById('attachment-list');
  const attachmentStatus = document.getElementById('attachment-status');
  const submitStatus = document.getElementById('submit-status');
  const submitButton = document.getElementById('submit-wish');
  const description = document.getElementById('description');
  const consent = document.getElementById('consent');
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
  description.addEventListener('input', () => description.setCustomValidity(''));
  consent.addEventListener('input', () => consent.setCustomValidity(''));

  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (sending || reading || completed) return;
    description.setCustomValidity(description.value.trim() ? '' : '请写下你希望怎样改进。');
    consent.setCustomValidity(consent.checked ? '' : '请先阅读并确认提交与公开说明。');
    if (!form.reportValidity()) return;
    requestId ??= makeRequestId();
    const data = new FormData(form);
    data.set('requestId', requestId);
    for (const name of ['description', 'email']) {
      data.set(name, String(data.get(name) || '').trim());
    }
    attachments.forEach(item => data.append('attachments', item.file, item.file.name));
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 180000);
    sending = true;
    fields.disabled = true;
    form.setAttribute('aria-busy', 'true');
    submitButton.textContent = '正在发送，请稍等…';
    setStatus(submitStatus, '正在发送愿望。若包含视频，可能需要一点时间，请不要关闭页面。');
    try {
      const response = await fetch('/api/wishes', {
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
      try { result = await response.json(); } catch { throw new Error('未收到有效的愿望编号，暂时无法确认提交结果。'); }
      if (!result || result.ok !== true || result.status !== 'pending_review' || typeof result.wishId !== 'string' || !result.wishId.trim() || result.wishId.length > 200 || /[\u0000-\u001f\u007f]/.test(result.wishId)) {
        throw new Error('未收到有效的愿望编号，暂时无法确认提交结果。');
      }
      completed = true;
      document.getElementById('wish-id').textContent = result.wishId;
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
      submitButton.textContent = '送出愿望 ↗';
      updateButtons();
    }
  });

  document.getElementById('send-another').addEventListener('click', () => {
    completed = false;
    setStatus(attachmentStatus, '');
    setStatus(submitStatus, '');
    document.getElementById('success-panel').hidden = true;
    document.getElementById('form-panel').hidden = false;
    description.focus();
  });
  window.addEventListener('beforeunload', event => {
    const hasText = ['description', 'email'].some(id => {
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

  const publicList = document.getElementById('wish-list');
  const listStatus = document.getElementById('list-status');
  const emptyState = document.getElementById('list-empty');
  const refreshButton = document.getElementById('refresh-wishes');
  const moreButton = document.getElementById('load-more');
  const statuses = { pending: '待处理', planned: '计划中', completed: '已完成 ✓' };
  let publicWishes = [];
  let nextCursor = null;
  let listLoading = false;
  let listHasLoaded = false;

  function readPublicWish(item) {
    if (!item || typeof item.id !== 'string' || !/^[A-Za-z0-9_-]{1,200}$/.test(item.id)
        || typeof item.description !== 'string' || !item.description.trim() || item.description.length > 5000
        || typeof item.status !== 'string' || !Object.prototype.hasOwnProperty.call(statuses, item.status)
        || (item.developerNote != null && (typeof item.developerNote !== 'string' || item.developerNote.length > 5000))) {
      throw new Error('收到的愿望列表格式不完整，请稍后刷新。');
    }
    // Only these explicitly public fields can reach the DOM. Never render media or email.
    return { id: item.id, description: item.description, status: item.status,
      developerNote: item.developerNote || '', updatedAt: typeof item.updatedAt === 'string' ? item.updatedAt.slice(0, 60) : '' };
  }

  function renderPublicWishes() {
    publicList.replaceChildren();
    for (const item of publicWishes) {
      const card = document.createElement('li');
      card.className = 'wish-card';
      const header = document.createElement('div');
      header.className = 'wish-card-header';
      const title = document.createElement('p');
      title.className = 'wish-card-title';
      title.textContent = `愿望 ${item.id.slice(0, 8)}`;
      const status = document.createElement('span');
      status.className = `wish-status ${item.status}`;
      status.textContent = statuses[item.status];
      header.append(title, status);
      const body = document.createElement('p');
      body.className = 'wish-description';
      body.textContent = item.description;
      card.append(header, body);
      if (item.developerNote) {
        const note = document.createElement('div');
        note.className = 'developer-note';
        const label = document.createElement('h3');
        label.textContent = item.status === 'completed' ? '完成说明' : '开发者说明';
        const text = document.createElement('p');
        text.textContent = item.developerNote;
        note.append(label, text);
        card.append(note);
      }
      const date = new Date(item.updatedAt);
      if (item.updatedAt && !Number.isNaN(date.getTime())) {
        const updated = document.createElement('p');
        updated.className = 'wish-updated';
        updated.textContent = `进度更新：${date.toLocaleDateString('zh-CN')}`;
        card.append(updated);
      }
      publicList.append(card);
    }
    emptyState.hidden = !listHasLoaded || publicWishes.length !== 0;
    moreButton.hidden = !nextCursor;
  }

  async function loadWishes(reset = true) {
    if (listLoading || (!reset && !nextCursor)) return;
    listLoading = true;
    refreshButton.disabled = true;
    moreButton.disabled = true;
    publicList.setAttribute('aria-busy', 'true');
    setStatus(listStatus, reset ? '正在读取公开愿望…' : '正在读取更多愿望…');
    const cursor = reset ? null : nextCursor;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetch(`/api/wishes?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, {
        method: 'GET', credentials: 'omit', cache: 'no-store', redirect: 'error',
        headers: { Accept: 'application/json' }, signal: controller.signal,
      });
      if (!response.ok) throw new Error(response.status === 429 ? '读取得有些频繁，请稍后再试。' : '暂时读不到公开愿望，请点击“刷新”重试。');
      let result;
      try { result = await response.json(); } catch { throw new Error('暂时读不到有效的愿望列表，请稍后刷新。'); }
      if (!result || result.ok !== true || !Array.isArray(result.items) || result.items.length > 50
          || !(result.nextCursor === null || (typeof result.nextCursor === 'string' && result.nextCursor.length > 0
          && result.nextCursor.length <= 1024 && !/[\u0000-\u001f\u007f]/.test(result.nextCursor)))
          || (cursor && result.nextCursor === cursor)) {
        throw new Error('收到的愿望列表格式不完整，请稍后刷新。');
      }
      const items = result.items.map(readPublicWish);
      const merged = new Map((reset ? [] : publicWishes).map(item => [item.id, item]));
      items.forEach(item => merged.set(item.id, item));
      publicWishes = Array.from(merged.values());
      nextCursor = result.nextCursor;
      listHasLoaded = true;
      renderPublicWishes();
      setStatus(listStatus, publicWishes.length ? `已显示 ${publicWishes.length} 条公开愿望。` : '');
    } catch (error) {
      const message = error.name === 'AbortError' ? '读取超时，请点击“刷新”重试。'
        : error instanceof TypeError ? '网络连接失败，请点击“刷新”重试。' : error.message;
      setStatus(listStatus, `${message}${publicWishes.length ? '\n上次读到的愿望仍保留在下面。' : ''}`, true);
      // A failed request is never presented as a verified empty list.
      if (!listHasLoaded) emptyState.hidden = true;
    } finally {
      window.clearTimeout(timeout);
      listLoading = false;
      refreshButton.disabled = false;
      moreButton.disabled = false;
      publicList.setAttribute('aria-busy', 'false');
    }
  }

  refreshButton.addEventListener('click', () => { void loadWishes(true); });
  moreButton.addEventListener('click', () => { void loadWishes(false); });
  void loadWishes(true);
})();
