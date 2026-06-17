(function () {
  'use strict';

  const API = '/api';
  let currentRunId = null;
  let eventSource = null;

  const $ = (id) => document.getElementById(id);

  const submitForm = $('submitForm');
  const supplementSection = $('supplementSection');
  const supplementBtn = $('supplementBtn');
  const refreshAuditBtn = $('refreshAuditBtn');
  const refreshPendingBtn = $('refreshPendingBtn');
  const runEvalBtn = $('runEvalBtn');
  const knowledgeForm = $('knowledgeForm');
  const refreshKnowledgeBtn = $('refreshKnowledgeBtn');
  const sampleIncomplete = $('sampleIncomplete');
  const sampleComplete = $('sampleComplete');

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

  function setKnowledgeLoading(loading) {
    $('knowledgeUploadBtn').disabled = loading;
  }

  function closeSSE() {
    if (eventSource) {
      eventSource.close();
      eventSource = null;
    }
  }

  function connectSSE(runId) {
    closeSSE();
    eventSource = new EventSource(`${API}/tickets/agent-runs/${runId}/stream`);
    eventSource.addEventListener('step', (e) => {
      try {
        const data = JSON.parse(e.data);
        appendStep(data.stepName, data.status, data.message, data.timestamp, false, 0);
      } catch (_) { /* ignore */ }
    });
    eventSource.onerror = () => closeSSE();
  }

  function clearSteps() {
    const list = $('stepList');
    list.innerHTML = '<li class="step-empty">等待 Agent 执行…</li>';
  }

  function appendStep(stepName, status, message, timestamp, llmUsed, costMs, isGate) {
    const list = $('stepList');
    const empty = list.querySelector('.step-empty');
    if (empty) empty.remove();

    const li = document.createElement('li');
    li.className = 'step-item' + (isGate ? ' step-gate' : '');
    const statusClass = status === 'SUCCESS' ? 'step-status-success' : 'step-status-failed';
    const time = timestamp ? new Date(timestamp).toLocaleTimeString() : '';
    let llmTag = '';
    if (llmUsed) {
      llmTag = '<span class="step-llm-tag">LLM</span>';
      if (costMs > 0) {
        llmTag += '<span class="step-cost"> ' + costMs + 'ms</span>';
      }
    }
    li.innerHTML =
      '<div><span class="step-name">' + esc(stepName) + '</span> ' +
      '<span class="' + statusClass + '">' + esc(status) + '</span>' + llmTag +
      (isGate ? ' <span class="step-llm-tag" style="background:rgba(245,158,11,0.2);color:#fcd34d">流程终止</span>' : '') +
      '</div>' +
      (time ? '<div class="step-meta">' + time + '</div>' : '') +
      (message ? '<div class="step-output">' + esc(truncate(message, 120)) + '</div>' : '');
    list.appendChild(li);
    list.scrollTop = list.scrollHeight;
  }

  function renderAuditSteps(steps, gateAt) {
    const list = $('stepList');
    list.innerHTML = '';
    if (!steps || steps.length === 0) {
      list.innerHTML = '<li class="step-empty">暂无步骤</li>';
      return;
    }
    steps.forEach((s) => {
      const isGate = gateAt && s.stepName === gateAt;
      appendStep(
        s.stepName,
        s.status,
        s.outputSnapshot,
        s.createdAt ? Date.parse(s.createdAt) : null,
        s.llmUsed,
        s.costMs || 0,
        isGate
      );
    });
  }

  function gateStepForResponse(data) {
    if (data.replyType === 'NEED_MORE_INFO') {
      return 'FOLLOW_UP_QUESTION_GENERATE';
    }
    return null;
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
    refreshAuditBtn.disabled = false;

    return gateStepForResponse(data);
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

  function truncate(str, len) {
    if (!str || str.length <= len) return str;
    return str.slice(0, len) + '…';
  }

  async function submitTicket(e) {
    e.preventDefault();
    setLoading(true);
    clearSteps();

    const payload = {
      sessionId: $('sessionId').value.trim(),
      userId: $('userId').value.trim(),
      title: $('title').value.trim() || undefined,
      content: $('content').value.trim(),
      source: $('source').value
    };

    try {
      const data = await apiFetch(`${API}/tickets/agent-runs`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      connectSSE(data.runId);
      const gateAt = renderResponse(data);
      await loadAudit(data.runId, gateAt);
      if (data.status === 'WAIT_HUMAN_CONFIRM') loadPending();
      showToast('工单已提交并完成分析');
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
      const gateAt = renderResponse(data);
      $('supplementContent').value = '';
      await loadAudit(currentRunId, gateAt);
      if (data.status === 'WAIT_HUMAN_CONFIRM') loadPending();
      showToast('补充信息已发送');
    } catch (err) {
      showToast(err.message, true);
    } finally {
      setLoading(false);
    }
  }

  async function loadAudit(runId, gateAt) {
    if (!runId) return;
    try {
      const steps = await apiFetch(`${API}/audit/agent-runs/${runId}`);
      renderAuditSteps(steps, gateAt);
    } catch (_) { /* optional */ }
  }

  async function runEval() {
    runEvalBtn.disabled = true;
    const box = $('evalResult');
    try {
      const report = await apiFetch(`${API}/evals/run`, { method: 'POST' });
      const passClass = report.failed === 0 ? 'eval-pass' : 'eval-fail';
      let html = '<p class="' + passClass + '">通过 ' + report.passed + ' / ' + report.total + '</p>';
      if (report.suiteName) {
        html += '<p class="hint">数据集：' + esc(report.suiteName) + '</p>';
      }
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
        html += '<ul>';
        report.failures.forEach((f) => { html += '<li class="eval-fail">' + esc(f) + '</li>'; });
        html += '</ul>';
      }
      box.innerHTML = html;
      showToast(report.failed === 0 ? 'Eval 全部通过' : 'Eval 存在失败', report.failed > 0);
    } catch (err) {
      box.innerHTML = '<p class="eval-fail">' + esc(err.message) + '</p>';
      showToast(err.message, true);
    } finally {
      runEvalBtn.disabled = false;
    }
  }

  async function uploadKnowledge(e) {
    e.preventDefault();
    const file = $('knowledgeFile').files[0];
    if (!file) {
      showToast('请选择知识文档', true);
      return;
    }
    setKnowledgeLoading(true);
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
      setKnowledgeLoading(false);
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
      const docs = await apiFetch(`${API}/knowledge/documents?limit=8`);
      if (!docs || docs.length === 0) {
        list.innerHTML = '<li class="knowledge-empty">暂无导入记录</li>';
        return;
      }
      list.innerHTML = docs.map((doc) => {
        const meta = [doc.systemName, doc.moduleName, doc.tags].filter(Boolean).join(' / ');
        return '<li class="knowledge-item">' +
          '<div><strong>' + esc(doc.title) + '</strong><span class="knowledge-source">' + esc(doc.sourceType || '') + '</span></div>' +
          (meta ? '<div class="step-meta">' + esc(meta) + '</div>' : '') +
          '<div class="knowledge-summary">' + esc(doc.summary || '') + '</div>' +
          '</li>';
      }).join('');
    } catch (err) {
      list.innerHTML = '<li class="knowledge-empty">加载失败</li>';
    }
  }

  async function loadPending() {
    const list = $('pendingList');
    try {
      const items = await apiFetch(`${API}/human/pending`);
      if (!items || items.length === 0) {
        list.innerHTML = '<li class="pending-empty">暂无待确认项</li>';
        return;
      }
      list.innerHTML = items.map((p) =>
        '<li class="pending-item" data-id="' + esc(p.id) + '" data-action="' + esc(p.actionType) + '">' +
        '<span><strong>' + esc(p.actionType) + '</strong> — Run ' + esc(p.runId.slice(0, 8)) + '…</span>' +
        '<span class="step-meta">' + esc(p.reason || '') + '</span>' +
        '<div class="pending-actions">' +
        '<button type="button" class="btn btn-sm btn-success confirm-btn">确认</button>' +
        '<button type="button" class="btn btn-sm btn-danger reject-btn">驳回</button>' +
        '</div></li>'
      ).join('');

      list.querySelectorAll('.confirm-btn').forEach((btn) => {
        btn.addEventListener('click', () => handlePending(btn.closest('.pending-item'), true));
      });
      list.querySelectorAll('.reject-btn').forEach((btn) => {
        btn.addEventListener('click', () => handlePending(btn.closest('.pending-item'), false));
      });
    } catch (err) {
      list.innerHTML = '<li class="pending-empty">加载失败</li>';
    }
  }

  async function handlePending(item, confirm) {
    const id = item.dataset.id;
    const actionType = item.dataset.action;
    const url = confirm
      ? `${API}/human/pending/${id}/confirm`
      : `${API}/human/pending/${id}/reject`;
    const body = confirm
      ? { confirmedBy: 'operator-web', actionType }
      : { confirmedBy: 'operator-web' };
    try {
      await apiFetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      showToast(confirm ? '已确认' : '已驳回');
      loadPending();
      if (currentRunId) {
        const run = await apiFetch(`${API}/tickets/agent-runs/${currentRunId}`);
        $('statusBadge').textContent = run.status;
      }
    } catch (err) {
      showToast(err.message, true);
    }
  }

  sampleIncomplete.addEventListener('click', () => {
    $('content').value = '接口报错了';
    $('title').value = '';
  });

  sampleComplete.addEventListener('click', () => {
    $('content').value =
      '生产环境，支付系统的 /pay/callback 接口 500，从上午 10 点开始，多个用户支付成功但订单状态还是待支付。';
    $('title').value = '支付回调异常';
  });

  submitForm.addEventListener('submit', submitTicket);
  knowledgeForm.addEventListener('submit', uploadKnowledge);
  supplementBtn.addEventListener('click', supplementMessage);
  refreshAuditBtn.addEventListener('click', () => loadAudit(currentRunId, null));
  refreshPendingBtn.addEventListener('click', loadPending);
  refreshKnowledgeBtn.addEventListener('click', loadKnowledge);
  runEvalBtn.addEventListener('click', runEval);

  loadPending();
  loadKnowledge();
})();
