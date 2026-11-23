/* TaskHub browser client. Vanilla JS, no build step, no third-party requests.
   Security notes:
   - tokens live in memory only (never localStorage) so an XSS cannot persist a session;
   - all server data is rendered with textContent, never innerHTML;
   - CSP is enforced by nginx (see nginx.conf). */

const state = {
  apiBase: window.location.origin,
  accessToken: null,
  refreshToken: null,
  user: null,
  page: 0,
  size: 20,
  sort: 'updatedAt',
  dir: 'desc',
  totalPages: 0,
  projects: [],
  members: [],
  editing: null,
};

const $ = (id) => document.getElementById(id);

/* ------------------------------------------------------------------ transport */

class ApiError extends Error {
  constructor(status, code, message, fieldErrors) {
    super(message);
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors || [];
  }
}

async function api(path, { method = 'GET', body, retry = true } = {}) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (state.accessToken) headers.Authorization = `Bearer ${state.accessToken}`;

  let response;
  try {
    response = await fetch(state.apiBase + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (networkError) {
    throw new ApiError(0, 'NETWORK_ERROR', `Cannot reach ${state.apiBase}. Check the server URL.`);
  }

  if (response.status === 401 && retry && state.refreshToken && await refreshSession()) {
    return api(path, { method, body, retry: false });
  }

  if (response.status === 204) return null;

  const text = await response.text();
  let payload = null;
  if (text) {
    try { payload = JSON.parse(text); } catch { /* non-JSON */ }
  }

  if (!response.ok) {
    throw new ApiError(response.status,
      payload?.code || `HTTP_${response.status}`,
      payload?.message || `Request failed (${response.status})`,
      payload?.fieldErrors);
  }
  return payload;
}

async function refreshSession() {
  try {
    const auth = await fetch(state.apiBase + '/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: state.refreshToken }),
    });
    if (!auth.ok) return false;
    applySession(await auth.json());
    return true;
  } catch {
    return false;
  }
}

function applySession(auth) {
  state.accessToken = auth.accessToken;
  state.refreshToken = auth.refreshToken;
  state.user = auth.user;
}

/* ---------------------------------------------------------------------- login */

$('server').value = window.location.origin;

$('login-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  hideError('login-error');
  $('login-button').disabled = true;
  state.apiBase = ($('server').value || window.location.origin).replace(/\/+$/, '');
  try {
    const auth = await api('/api/v1/auth/login', {
      method: 'POST',
      body: { email: $('email').value, password: $('password').value },
    });
    applySession(auth);
    $('password').value = '';
    await enterWorkspace();
  } catch (error) {
    showError('login-error', error.status === 401 ? 'Email or password is incorrect.' : error.message);
  } finally {
    $('login-button').disabled = false;
  }
});

async function enterWorkspace() {
  $('login-view').hidden = true;
  $('workspace').hidden = false;
  $('server-pill').textContent = new URL(state.apiBase).host;
  $('user-label').textContent = state.user ? state.user.displayName : '';
  $('theme-toggle').textContent = document.documentElement.dataset.theme === 'dark' ? 'Light' : 'Dark';

  buildSelects();
  await loadProjects();
  await loadTasks();
}

$('logout').addEventListener('click', async () => {
  try {
    if (state.accessToken) {
      await api('/api/v1/auth/logout', { method: 'POST', body: { refreshToken: state.refreshToken } });
    }
  } catch { /* best effort */ }
  state.accessToken = null;
  state.refreshToken = null;
  state.user = null;
  $('workspace').hidden = true;
  $('login-view').hidden = false;
  $('task-rows').replaceChildren();
  // Always return to the sign-in form: after a sign-up the register form would otherwise
  // still be the visible pane on the auth screen.
  showLogin();
});

/* --------------------------------------------------------------------- filters */

function buildSelects() {
  fillSelect($('status-filter'), ['TODO', 'IN_PROGRESS', 'BLOCKED', 'DONE'], 'All statuses');
  fillSelect($('priority-filter'), ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'], 'All priorities');
  fillSelect($('task-status'), ['TODO', 'IN_PROGRESS', 'BLOCKED', 'DONE'], null);
  fillSelect($('task-priority'), ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'], null);
  fillSelect($('project-status'), ['PLANNED', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'ARCHIVED'], null);

  $('status-filter').addEventListener('change', () => { state.page = 0; loadTasks(); });
  $('priority-filter').addEventListener('change', () => { state.page = 0; loadTasks(); });
  $('project-filter').addEventListener('change', () => { state.page = 0; loadTasks(); });
  $('overdue-filter').addEventListener('change', () => { state.page = 0; loadTasks(); });
  $('page-size').addEventListener('change', () => { state.size = Number($('page-size').value); state.page = 0; loadTasks(); });

  let debounce;
  $('search').addEventListener('input', () => {
    clearTimeout(debounce);
    debounce = setTimeout(() => { state.page = 0; loadTasks(); }, 300);
  });

  $('clear-filters').addEventListener('click', () => {
    $('search').value = '';
    $('project-filter').value = '';
    $('status-filter').value = '';
    $('priority-filter').value = '';
    $('overdue-filter').checked = false;
    state.page = 0;
    loadTasks();
  });

  $('prev-page').addEventListener('click', () => { if (state.page > 0) { state.page -= 1; loadTasks(); } });
  $('next-page').addEventListener('click', () => { if (state.page + 1 < state.totalPages) { state.page += 1; loadTasks(); } });

  document.querySelectorAll('th[data-sort]').forEach((th) => {
    th.addEventListener('click', () => {
      const field = th.dataset.sort;
      if (state.sort === field) {
        state.dir = state.dir === 'asc' ? 'desc' : 'asc';
      } else {
        state.sort = field;
        state.dir = field === 'updatedAt' || field === 'dueDate' ? 'desc' : 'asc';
      }
      state.page = 0;
      loadTasks();
    });
  });
}

function fillSelect(select, values, allLabel) {
  select.replaceChildren();
  if (allLabel) select.append(new Option(allLabel, ''));
  values.forEach((value) => select.append(new Option(label(value), value)));
}

const label = (value) => value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ');

function humanize(value) {
  return value ? label(value) : '-';
}

/* ------------------------------------------------------------------------ data */

async function loadProjects() {
  try {
    const page = await api('/api/v1/projects?size=100&sort=name&direction=asc');
    state.projects = page.content;
    const filter = $('project-filter');
    const current = filter.value;
    filter.replaceChildren(new Option('All projects', ''));
    state.projects.forEach((project) => filter.append(new Option(`${project.code} - ${project.name}`, project.id)));
    filter.value = current && state.projects.some((p) => p.id === current) ? current : '';

    const taskProject = $('task-project');
    taskProject.replaceChildren();
    state.projects.forEach((project) => taskProject.append(new Option(`${project.code} - ${project.name}`, project.id)));
  } catch (error) {
    toast(error.message, true);
  }
}

async function loadTasks() {
  const params = new URLSearchParams({ page: state.page, size: state.size, sort: state.sort, direction: state.dir });
  if ($('search').value.trim()) params.set('search', $('search').value.trim());
  if ($('project-filter').value) params.set('projectId', $('project-filter').value);
  if ($('status-filter').value) params.set('status', $('status-filter').value);
  if ($('priority-filter').value) params.set('priority', $('priority-filter').value);
  if ($('overdue-filter').checked) params.set('overdue', 'true');

  try {
    const page = await api(`/api/v1/tasks?${params}`);
    renderRows(page.content);
    state.totalPages = page.totalPages;
    state.page = page.page;
    const from = page.totalElements === 0 ? 0 : page.page * page.size + 1;
    const to = Math.min((page.page + 1) * page.size, page.totalElements);
    $('result-summary').textContent = `Showing ${from}-${to} of ${page.totalElements} tasks`;
    $('page-label').textContent = `Page ${page.totalPages === 0 ? 0 : page.page + 1} of ${page.totalPages}`;
    $('prev-page').disabled = page.first;
    $('next-page').disabled = page.last;
    $('empty-state').hidden = page.totalElements !== 0;
  } catch (error) {
    if (error.status === 401) { toast('Session expired. Sign in again.', true); $('logout').click(); return; }
    toast(error.message, true);
  }
}

function renderRows(tasks) {
  const tbody = $('task-rows');
  tbody.replaceChildren();
  tasks.forEach((task) => {
    const tr = document.createElement('tr');

    const title = document.createElement('td');
    const strong = document.createElement('div');
    strong.className = 'cell-title';
    strong.textContent = task.title;
    title.append(strong);

    const project = document.createElement('td');
    project.textContent = task.project ? task.project.name : '-';

    const status = document.createElement('td');
    status.append(badge(humanize(task.status), `badge-${task.status.toLowerCase()}`));

    const priority = document.createElement('td');
    priority.append(badge(humanize(task.priority), `badge-${task.priority.toLowerCase()}`));

    const due = document.createElement('td');
    due.textContent = task.dueDate || '-';
    if (task.overdue) due.className = 'overdue';

    const assignee = document.createElement('td');
    assignee.textContent = task.assignee ? task.assignee.displayName : 'Unassigned';

    const updated = document.createElement('td');
    updated.textContent = new Date(task.updatedAt).toLocaleString();

    const actions = document.createElement('td');
    actions.className = 'row-actions';
    const edit = document.createElement('button');
    edit.type = 'button';
    edit.className = 'ghost small';
    edit.textContent = 'Edit';
    edit.addEventListener('click', () => openTaskDialog(task));
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'ghost small danger';
    remove.textContent = 'Delete';
    remove.addEventListener('click', () => deleteTask(task));
    actions.append(edit, remove);

    tr.append(title, project, status, priority, due, assignee, updated, actions);
    tbody.append(tr);
  });
}

function badge(text, className) {
  const span = document.createElement('span');
  span.className = `badge ${className}`;
  span.textContent = text;
  return span;
}

/* ------------------------------------------------------------------ task dialog */

$('new-task').addEventListener('click', () => {
  if (!state.projects.length) { toast('Create a project first.', true); openProjectDialog(); return; }
  openTaskDialog(null);
});

async function openTaskDialog(task) {
  state.editing = task;
  state.members = [];
  $('task-dialog-title').textContent = task ? 'Edit task' : 'New task';
  $('task-project').value = task && task.project ? task.project.id : (state.projects[0]?.id || '');
  $('task-project').disabled = Boolean(task);
  $('task-title').value = task ? task.title : '';
  $('task-description').value = task && task.description ? task.description : '';
  $('task-status').value = task ? task.status : 'TODO';
  $('task-priority').value = task ? task.priority : 'MEDIUM';
  $('task-due').value = task && task.dueDate ? task.dueDate : '';
  hideError('task-error');
  await loadMembers($('task-project').value);
  $('task-assignee').value = task && task.assignee ? task.assignee.id : '';
  $('task-dialog').showModal();
}

$('task-project').addEventListener('change', () => loadMembers($('task-project').value));

async function loadMembers(projectId) {
  const select = $('task-assignee');
  select.replaceChildren(new Option('Unassigned', ''));
  select.disabled = true;
  if (!projectId) return;
  try {
    state.members = await api(`/api/v1/projects/${projectId}/members`);
    state.members.forEach((member) => select.append(new Option(member.user.displayName, member.user.id)));
  } catch (error) {
    toast(`Could not load members: ${error.message}`, true);
  } finally {
    select.disabled = false;
  }
}

$('task-cancel').addEventListener('click', () => $('task-dialog').close());

$('task-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  hideError('task-error');
  const payload = {
    title: $('task-title').value.trim(),
    description: $('task-description').value.trim() || null,
    status: $('task-status').value,
    priority: $('task-priority').value,
    dueDate: $('task-due').value || null,
    assigneeId: $('task-assignee').value || null,
  };

  if (payload.title.length < 2) { showError('task-error', 'Title must be at least 2 characters.'); return; }

  try {
    if (state.editing) {
      payload.clearDueDate = !payload.dueDate;
      payload.clearAssignee = !payload.assigneeId;
      payload.version = state.editing.version;
      await api(`/api/v1/tasks/${state.editing.id}`, { method: 'PATCH', body: payload });
      toast('Task updated');
    } else {
      payload.projectId = $('task-project').value;
      await api('/api/v1/tasks', { method: 'POST', body: payload });
      toast('Task created');
    }
    $('task-dialog').close();
    await loadTasks();
  } catch (error) {
    showError('task-error', error.message + (error.fieldErrors.length
      ? ` (${error.fieldErrors.map((f) => `${f.field}: ${f.message}`).join('; ')})` : ''));
  }
});

async function deleteTask(task) {
  if (!window.confirm(`Delete "${task.title}"? This cannot be undone.`)) return;
  try {
    await api(`/api/v1/tasks/${task.id}`, { method: 'DELETE' });
    toast('Task deleted');
    await loadTasks();
  } catch (error) {
    toast(error.message, true);
  }
}

/* --------------------------------------------------------------- project dialog */

$('new-project').addEventListener('click', openProjectDialog);

function openProjectDialog() {
  $('project-code').value = '';
  $('project-name').value = '';
  $('project-status').value = 'PLANNED';
  hideError('project-error');
  $('project-dialog').showModal();
}

$('project-cancel').addEventListener('click', () => $('project-dialog').close());

$('project-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  try {
    const project = await api('/api/v1/projects', {
      method: 'POST',
      body: {
        code: $('project-code').value.trim().toUpperCase(),
        name: $('project-name').value.trim(),
        status: $('project-status').value,
      },
    });
    toast(`Project ${project.code} created`);
    $('project-dialog').close();
    await loadProjects();
    await loadTasks();
  } catch (error) {
    showError('project-error', error.message);
  }
});

/* -------------------------------------------------------------------- theming */

$('theme-toggle').addEventListener('click', () => {
  const dark = document.documentElement.dataset.theme === 'dark';
  document.documentElement.dataset.theme = dark ? 'light' : 'dark';
  $('theme-toggle').textContent = dark ? 'Dark' : 'Light';
  try { sessionStorage.setItem('taskhub.theme', document.documentElement.dataset.theme); } catch { /* ignore */ }
});

try {
  const saved = sessionStorage.getItem('taskhub.theme');
  if (saved) document.documentElement.dataset.theme = saved;
} catch { /* ignore */ }

/* --------------------------------------------------------------------- toasts */

function toast(message, isError = false) {
  const node = document.createElement('div');
  node.className = `toast ${isError ? 'toast-error' : 'toast-success'}`;
  node.textContent = message;
  $('toasts').append(node);
  setTimeout(() => node.remove(), 4000);
}

function showError(id, message) {
  const node = $(id);
  node.textContent = message;
  node.hidden = false;
}

function hideError(id) {
  $(id).hidden = true;
}

/* ----------------------------------------------------------------- registration */

function normalizeBase(raw) {
  const value = (raw || window.location.origin).trim().replace(/\/+$/, '');
  return /^https?:\/\//i.test(value) ? value : 'https://' + value;
}

function showRegister() {
  hideError('login-error');
  hideError('register-error');
  $('reg-server').value = $('server').value || window.location.origin;
  $('reg-email').value = $('email').value || '';
  $('login-form').hidden = true;
  $('register-form').hidden = false;
  $('reg-name').focus();
}

function showLogin() {
  hideError('register-error');
  hideError('login-error');
  $('server').value = $('reg-server').value || $('server').value;
  $('email').value = $('reg-email').value || $('email').value;
  $('register-form').hidden = true;
  $('login-form').hidden = false;
  $('password').focus();
}

$('show-register').addEventListener('click', showRegister);
$('show-login').addEventListener('click', showLogin);

$('register-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  hideError('register-error');

  const name = $('reg-name').value.trim();
  const email = $('reg-email').value.trim();
  const password = $('reg-password').value;
  const confirm = $('reg-confirm').value;

  if (name.length < 2) return showError('register-error', 'Display name must be at least 2 characters.');
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return showError('register-error', 'Enter a valid email address.');
  if (password.length < 12) return showError('register-error', 'Password must be at least 12 characters.');
  if (!/[A-Za-z]/.test(password) || !/\d/.test(password)) {
    return showError('register-error', 'Password must contain at least one letter and one digit.');
  }
  if (password !== confirm) return showError('register-error', 'Passwords do not match.');

  state.apiBase = normalizeBase($('reg-server').value);
  $('register-button').disabled = true;
  try {
    const auth = await api('/api/v1/auth/register', {
      method: 'POST',
      body: { email, password, displayName: name },
    });
    applySession(auth);
    $('reg-password').value = '';
    $('reg-confirm').value = '';
    await enterWorkspace();
    toast(`Welcome to TaskHub, ${auth.user.displayName}`);
  } catch (error) {
    const message = error.code === 'EMAIL_ALREADY_REGISTERED'
      ? 'That email is already registered - sign in instead.'
      : error.code === 'REGISTRATION_DISABLED'
        ? 'Sign-ups are closed on this server. Ask an administrator for an account.'
        : error.status === 429
          ? 'Too many attempts. Wait a few minutes and try again.'
          : error.message;
    showError('register-error', message);
  } finally {
    $('register-button').disabled = false;
  }
});
