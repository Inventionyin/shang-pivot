/* Shang 后台原型 —— 弹窗 / 抽屉控制器（原生，无依赖）
   用法：
     触发： <button data-modal-open="dlg-del">删除</button>
     弹窗： <div class="modal" id="dlg-del" hidden>…<button data-modal-close>取消</button>…</div>
   约定：ESC 关闭；点遮罩关闭；带 data-modal-static 的只能点按钮关。
   交付到 Vue 时替换为 ElMessageBox / ElDialog / ElDrawer，行为一致。 */
(function () {
  function close(el) {
    if (!el || el.hasAttribute('data-modal-static')) return;
    el.hidden = true;
    document.body.style.overflow = '';
  }
  function open(el) {
    if (!el) return;
    el.hidden = false;
    document.body.style.overflow = 'hidden';
    var f = el.querySelector('[autofocus], .btn-primary, .btn-danger');
    if (f) f.focus({ preventScroll: true });
  }
  document.addEventListener('click', function (e) {
    var t = e.target;
    var opener = t.closest && t.closest('[data-modal-open]');
    if (opener) {
      e.preventDefault();
      open(document.getElementById(opener.getAttribute('data-modal-open')));
      return;
    }
    var closer = t.closest && t.closest('[data-modal-close]');
    if (closer) {
      e.preventDefault();
      close(closer.closest('.modal, .drawer'));
      return;
    }
    if (t.classList && (t.classList.contains('modal-mask'))) {
      close(t.closest('.modal, .drawer'));
    }
  });
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape') return;
    var list = document.querySelectorAll('.modal:not([hidden]), .drawer:not([hidden])');
    if (list.length) close(list[list.length - 1]);
  });
})();