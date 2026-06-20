(function () {
  'use strict';

  const API = '/api';
  // 固定会话/用户标识——验证 Agent 效果时这些字段不是关注点，去掉输入框减少噪音。
  const SESSION_ID = 'sess-web-' + Date.now();
  const USER_ID = 'u-1001';
  let currentRunId = null;

  const $ = (id) => document.getElementById(id);

  const submitForm = $('submitForm');
  const supplementSection = $('supplementSection');
  const supplementBtn = $('supplementBtn');
  const sampleIncomplete = $('sampleIncomplete');
  const sampleComplete = $('sampleComplete');
  const knowledgeForm = $('knowledgeForm');
  const refreshKnowledgeBtn = $('refreshKnowledgeBtn');
  const confirmBtn = $('confirmBtn');
  const rejectBtn = $('rejectBtn');
  // 当前 WAIT_HUMAN_CONFIRM 对应的 pendingAction id（用于确认/驳回）
  let currentPendingId = null;

  function showToast(msg, isError) {
    const toast = $('toast');
    toast.textContent = msg;
    toast.classList.toggle('error', !!isError);
    toast.classList.remove('hidden');
    setTimeout(() => toast.classList.add('hidden'), 3500);
  }

  async function apiFetch(url, options) {
    const res = await fetch(url, options);
    const body = await res.json().catch(() => null);
    if (!res.ok) {
      const msg = body?.message || body?.error || `请求失败 (${res.status})`;
      throw new Error(msg);
    }
    return body;
  }

  function setLoading(loading) {
    $('submitBtn').disabled = loading;
    supplementBtn.disabled = loading;
  }

  function renderResponse(data) {
    currentRunId = data.runId;
    $('emptyState').classList.add('hidden');
    $('statusBar').classList.remove('hidden');
    $('runIdDisplay').textContent = data.runId;
    $('statusBadge').textContent = data.status;
    $('replyBadge').textContent = data.replyType;

    const aiBadge = $('aiBadge');
    if (data.aiGenerated) {
      aiBadge.classList.remove('hidden');
    } else {
      aiBadge.classList.add('hidden');
    }

    const msgBox = $('messageBox');
    if (data.message) {
      msgBox.textContent = data.message;
      msgBox.classList.remove('hidden');
    } else {
      msgBox.classList.add('hidden');
    }

    const qBox = $('questionsBox');
    if (data.questions && data.questions.length > 0) {
      qBox.innerHTML = '<h4>追问问题</h4><ol>' +
        data.questions.map((q) => '<li>' + esc(q) + '</li>').join('') + '</ol>';
      qBox.classList.remove('hidden');
    } else {
      qBox.classList.add('hidden');
    }

    const analysisBox = $('analysisBox');
    if (data.analysis) {
      analysisBox.innerHTML = renderAnalysis(data.analysis, data.aiGenerated);
      analysisBox.classList.remove('hidden');
    } else {
      analysisBox.classList.add('hidden');
    }

    supplementSection.classList.toggle('hidden', data.status !== 'WAIT_USER_INPUT');

    // HITL：P1/P2 工单进入 WAIT_HUMAN_CONFIRM 时，显示确认/驳回按钮
    const confirmBox = $('confirmBox');
    if (data.status === 'WAIT_HUMAN_CONFIRM' && data.analysis && data.analysis.humanConfirm) {
      $('confirmReason').textContent = '⚠ 需要人工确认：' + (data.analysis.humanConfirm.reason || '高优先级工单');
      confirmBox.classList.remove('hidden');
      loadPendingForCurrentRun();
    } else {
      confirmBox.classList.add('hidden');
      currentPendingId = null;
    }
  }

  function renderAnalysis(a, aiGenerated) {
    const t = a.ticket || {};
    const r = a.routing || {};
    const s = a.suggestion || {};
    const h = a.humanConfirm || {};
    const rc = a.rootCause || {};
    const pri = t.priority || '—';
    let html = '';

    if (aiGenerated) {
      html += '<p class="hint" style="margin-bottom:0.75rem">以下摘要与建议含 AI 生成内容；优先级与路由由规则引擎决定。</p>';
    }

    html += '<div class="analysis-card">';
    html += '<h3>工单摘要</h3>';
    if (pri !== '—') {
      html += '<span class="priority priority-' + esc(pri) + '">' + esc(pri) + '</span> ';
    }
    html += '<dl>';
    if (t.summary) html += row('摘要', t.summary);
    if (t.issueType) html += row('类型', t.issueType);
    if (t.affectedSystem) html += row('系统', t.affectedSystem);
    if (t.affectedModule) html += row('模块', t.affectedModule);
    if (t.impactScope) html += row('影响', t.impactScope);
    html += '</dl></div>';

    if (rc.hypothesis) {
      html += '<div class="analysis-card" style="border-left:3px solid #f59e0b"><h3>根因分析</h3>';
      html += '<p style="font-weight:600;margin-bottom:0.5rem">' + esc(rc.hypothesis) + '</p>';
      html += '<p class="hint" style="margin-bottom:0.25rem">置信度: ' + (rc.confidence != null ? (rc.confidence * 100).toFixed(0) + '%' : '—') + '</p>';
      if (rc.evidence && rc.evidence.length) {
        html += '<p class="hint" style="margin-top:0.5rem">证据链</p><ul>';
        rc.evidence.forEach((e) => { html += '<li>' + esc(e) + '</li>'; });
        html += '</ul>';
      }
      if (rc.unknowns && rc.unknowns.length) {
        html += '<p class="hint" style="margin-top:0.5rem">待确认项</p><ul>';
        rc.unknowns.forEach((u) => { html += '<li>' + esc(u) + '</li>'; });
        html += '</ul>';
      }
      html += '</div>';
    }

    if (r.primaryTeam) {
      html += '<div class="analysis-card"><h3>团队路由 <span class="hint">（规则）</span></h3><dl>';
      html += row('主责团队', r.primaryTeam);
      if (r.backupTeams && r.backupTeams.length) {
        html += row('备份团队', r.backupTeams.join('、'));
      }
      if (r.routingReason) html += row('原因', r.routingReason);
      html += '</dl></div>';
    }

    if ((s.possibleCauses && s.possibleCauses.length) || (s.actions && s.actions.length)) {
      html += '<div class="analysis-card"><h3>处理建议';
      if (aiGenerated) html += ' <span class="ai-badge" style="font-size:0.7rem">AI</span>';
      html += '</h3>';
      if (s.possibleCauses && s.possibleCauses.length) {
        html += '<p class="hint">可能原因</p><ul>';
        s.possibleCauses.forEach((c) => { html += '<li>' + esc(c) + '</li>'; });
        html += '</ul>';
      }
      if (s.runbookSteps && s.runbookSteps.length) {
        html += '<p class="hint" style="margin-top:0.5rem">Runbook</p><ol>';
        s.runbookSteps.forEach((c) => { html += '<li>' + esc(c) + '</li>'; });
        html += '</ol>';
      } else if (s.actions && s.actions.length) {
        html += '<p class="hint" style="margin-top:0.5rem">建议动作</p><ul>';
        s.actions.forEach((c) => { html += '<li>' + esc(c) + '</li>'; });
        html += '</ul>';
      }
      if (s.sources && s.sources.length) {
        html += '<p class="hint" style="margin-top:0.5rem">引用来源</p><ul>';
        s.sources.forEach((c) => { html += '<li>' + esc(c) + '</li>'; });
        html += '</ul>';
      }
      html += '</div>';
    }

    if (h.required) {
      html += '<div class="human-alert">⚠ 需要人工确认：' + esc(h.reason || '高优先级工单') + '</div>';
    }
    return html;
  }

  function row(label, value) {
    return '<dt>' + esc(label) + '</dt><dd>' + esc(value) + '</dd>';
  }

  function esc(str) {
    if (str == null) return '';
    const d = document.createElement('div');
    d.textContent = String(str);
    return d.innerHTML;
  }

  async function submitTicket(e) {
    e.preventDefault();
    setLoading(true);

    const payload = {
      sessionId: SESSION_ID,
      userId: USER_ID,
      content: $('content').value.trim(),
      source: 'WEB'
    };

    try {
      const data = await apiFetch(`${API}/tickets/agent-runs`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      renderResponse(data);
      showToast(data.status === 'WAIT_USER_INPUT' ? 'Agent 需要更多信息' : '工单已分析完成');
    } catch (err) {
      showToast(err.message, true);
    } finally {
      setLoading(false);
    }
  }

  async function supplementMessage() {
    if (!currentRunId) return;
    const content = $('supplementContent').value.trim();
    if (!content) {
      showToast('请输入补充内容', true);
      return;
    }
    setLoading(true);
    try {
      const data = await apiFetch(`${API}/tickets/agent-runs/${currentRunId}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content })
      });
      renderResponse(data);
      $('supplementContent').value = '';
      showToast('补充信息已发送');
    } catch (err) {
      showToast(err.message, true);
    } finally {
      setLoading(false);
    }
  }

  sampleIncomplete.addEventListener('click', () => {
    $('content').value = '接口报错了';
  });

  sampleComplete.addEventListener('click', () => {
    $('content').value =
      '生产环境，支付系统的 /pay/callback 接口 500，从上午 10 点开始，多个用户支付成功但订单状态还是待支付。';
  });

  // ---- HITL 人工确认 ----

  // 找到当前 run 对应的 pendingAction，记录其 id 供确认/驳回使用
  async function loadPendingForCurrentRun() {
    if (!currentRunId) return;
    try {
      const items = await apiFetch(`${API}/human/pending`);
      const match = items && items.find((p) => p.runId === currentRunId);
      currentPendingId = match ? match.id : null;
    } catch (_) { /* 可选，忽略 */ }
  }

  async function handleConfirm(confirm) {
    if (!currentPendingId) {
      showToast('未找到待确认项', true);
      return;
    }
    const url = confirm
      ? `${API}/human/pending/${currentPendingId}/confirm`
      : `${API}/human/pending/${currentPendingId}/reject`;
    const body = confirm
      ? { confirmedBy: 'operator-web' }
      : { confirmedBy: 'operator-web' };
    try {
      const run = await apiFetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      $('statusBadge').textContent = run.status;
      $('confirmBox').classList.add('hidden');
      showToast(confirm ? '已确认' : '已驳回');
    } catch (err) {
      showToast(err.message, true);
    }
  }

  // ---- 知识库上传 ----

  async function uploadKnowledge(e) {
    e.preventDefault();
    const file = $('knowledgeFile').files[0];
    if (!file) {
      showToast('请选择知识文档', true);
      return;
    }
    $('knowledgeUploadBtn').disabled = true;
    try {
      const formData = new FormData();
      formData.append('file', file);
      appendFormValue(formData, 'title', $('knowledgeTitle').value);
      appendFormValue(formData, 'systemName', $('knowledgeSystem').value);
      appendFormValue(formData, 'moduleName', $('knowledgeModule').value);
      appendFormValue(formData, 'tags', $('knowledgeTags').value);

      const result = await apiFetch(`${API}/knowledge/documents`, {
        method: 'POST',
        body: formData
      });
      knowledgeForm.reset();
      await loadKnowledge();
      showToast('已导入 ' + result.chunks + ' 个知识片段' + (result.vectorIndexed ? '，并写入向量索引' : ''));
    } catch (err) {
      showToast(err.message, true);
    } finally {
      $('knowledgeUploadBtn').disabled = false;
    }
  }

  function appendFormValue(formData, key, value) {
    const trimmed = value == null ? '' : value.trim();
    if (trimmed) {
      formData.append(key, trimmed);
    }
  }

  async function loadKnowledge() {
    const list = $('knowledgeList');
    try {
      const docs = await apiFetch(`${API}/knowledge/documents?limit=10`);
      if (!docs || docs.length === 0) {
        list.innerHTML = '<li class="knowledge-empty">暂无导入记录（启动时自带种子知识，未计入此列表）</li>';
        return;
      }
      list.innerHTML = docs.map((doc) => {
        const meta = [doc.systemName, doc.moduleName].filter(Boolean).join(' / ');
        return '<li class="knowledge-item">' +
          '<div><strong>' + esc(doc.title) + '</strong>' +
          (doc.sourceType ? '<span class="knowledge-source">' + esc(doc.sourceType) + '</span></div>' : '</div>') +
          (meta ? '<div class="step-meta">' + esc(meta) + '</div>' : '') +
          (doc.summary ? '<div class="knowledge-summary">' + esc(doc.summary) + '</div>' : '') +
          '</li>';
      }).join('');
    } catch (err) {
      list.innerHTML = '<li class="knowledge-empty">加载失败</li>';
    }
  }

  // ---- 回归 Eval ----

  async function runEval() {
    const btn = $('runEvalBtn');
    const box = $('evalResult');
    btn.disabled = true;
    box.innerHTML = '<p class="hint">运行中（22 个样本逐条调 LLM，约 1-2 分钟）…</p>';
    try {
      const report = await apiFetch(`${API}/evals/run`, { method: 'POST' });
      const passClass = report.failed === 0 ? 'eval-pass' : 'eval-fail';
      let html = '<p class="' + passClass + '">通过 ' + report.passed + ' / ' + report.total + '</p>';
      if (report.groups && report.groups.length) {
        html += '<div class="eval-groups">';
        report.groups.forEach((group) => {
          const groupClass = group.failed === 0 ? 'eval-pass' : 'eval-fail';
          html += '<div class="eval-group-item">' +
            '<span>' + esc(group.name) + '</span>' +
            '<span class="' + groupClass + '">' + group.passed + ' / ' + group.total + '</span>' +
            '</div>';
        });
        html += '</div>';
      }
      if (report.failures && report.failures.length) {
        html += '<p class="hint" style="margin-top:0.5rem">失败项：</p><ul class="eval-failures">';
        report.failures.forEach((f) => { html += '<li class="eval-fail">' + esc(f) + '</li>'; });
        html += '</ul>';
      }
      box.innerHTML = html;
      showToast(report.failed === 0 ? 'Eval 全部通过' : 'Eval 存在 ' + report.failed + ' 个失败', report.failed > 0);
    } catch (err) {
      box.innerHTML = '<p class="eval-fail">' + esc(err.message) + '</p>';
      showToast(err.message, true);
    } finally {
      btn.disabled = false;
    }
  }

  submitForm.addEventListener('submit', submitTicket);
  supplementBtn.addEventListener('click', supplementMessage);
  confirmBtn.addEventListener('click', () => handleConfirm(true));
  rejectBtn.addEventListener('click', () => handleConfirm(false));
  knowledgeForm.addEventListener('submit', uploadKnowledge);
  refreshKnowledgeBtn.addEventListener('click', loadKnowledge);
  $('runEvalBtn').addEventListener('click', runEval);

  loadKnowledge();
})();
