/* 尚品汇 C 端 —— 弹窗 / 半屏控制器（原生，无依赖）
   用法：
     触发： <button data-modal-open="dlg-del">删除</button>
     弹窗： <div class="modal" id="dlg-del" hidden>…<button data-modal-close>取消</button>…</div>
     半屏： <div class="sheet" id="sh-spec" hidden>…</div>
   约定：ESC 关闭；点遮罩关闭；带 data-modal-static 的只能点按钮关。
   交付到 Vue 时替换为 ElDialog / ElMessageBox（Web）与 van-popup（H5），行为一致。 */
(function () {
  function panelOf(el) { return el && el.querySelector('.modal-panel, .sheet-panel'); }

  function close(el) {
    if (!el || el.hasAttribute('data-modal-static')) return;
    el.hidden = true;
    document.body.style.overflow = '';
  }

  function open(el) {
    if (!el) return;
    el.hidden = false;
    document.body.style.overflow = 'hidden';
    var f = el.querySelector('[autofocus], .input, .btn-primary, .btn-danger');
    if (f) f.focus({ preventScroll: true });
  }

  /* 60 秒倒计时（登录验证码）：
     <button data-countdown="60">获取验证码</button> */
  function startCountdown(btn) {
    if (btn.disabled || btn.dataset.counting === '1') return;
    var left = parseInt(btn.getAttribute('data-countdown') || '60', 10);
    var text = btn.textContent;
    btn.dataset.counting = '1';
    btn.disabled = true;
    btn.classList.add('is-counting');
    btn.textContent = left + 's 后重发';
    var timer = setInterval(function () {
      left -= 1;
      if (left <= 0) {
        clearInterval(timer);
        btn.dataset.counting = '0';
        btn.disabled = false;
        btn.classList.remove('is-counting');
        btn.textContent = text;
        return;
      }
      btn.textContent = left + 's 后重发';
    }, 1000);
  }

  /* 支付：loading → 成功 / 失败（同一弹窗内三态切换） */
  function payTo(el, state) {
    var stages = el.querySelectorAll('[data-pay-stage]');
    for (var i = 0; i < stages.length; i++) {
      stages[i].hidden = stages[i].getAttribute('data-pay-stage') !== state;
    }
  }

  document.addEventListener('click', function (e) {
    var t = e.target;
    if (!t.closest) return;

    var opener = t.closest('[data-modal-open]');
    if (opener) {
      e.preventDefault();
      /* 同时带 data-modal-close 的按钮：先关掉当前层再打开目标层（弹窗间切换） */
      if (opener.hasAttribute('data-modal-close')) close(opener.closest('.modal, .sheet'));
      var target = document.getElementById(opener.getAttribute('data-modal-open'));
      open(target);
      var pay = opener.getAttribute('data-pay');
      if (target && pay) payTo(target, 'loading');
      return;
    }

    var closer = t.closest('[data-modal-close]');
    if (closer) {
      e.preventDefault();
      close(closer.closest('.modal, .sheet'));
      return;
    }

    var payBtn = t.closest('[data-pay-to]');
    if (payBtn) {
      e.preventDefault();
      var host = payBtn.closest('.modal, .sheet');
      if (host) payTo(host, payBtn.getAttribute('data-pay-to'));
      return;
    }

    var cd = t.closest('[data-countdown]');
    if (cd) {
      e.preventDefault();
      startCountdown(cd);
      return;
    }

    if (t.classList && (t.classList.contains('modal-mask') || t.classList.contains('sheet-mask'))) {
      close(t.closest('.modal, .sheet'));
    }
  });

  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape') return;
    var list = document.querySelectorAll('.modal:not([hidden]):not(.is-inline), .sheet:not([hidden]):not(.is-inline)');
    if (list.length) close(list[list.length - 1]);
  });
})();