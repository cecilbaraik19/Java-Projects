/* Light / dark mode. Runs before the page is drawn so there is no flash.
   - If the person picked a mode before, use it (saved in this browser).
   - Otherwise follow the computer's setting (prefers-color-scheme). */
(function () {
  var saved = null;
  try { saved = localStorage.getItem('qpg-theme'); } catch (e) { /* storage blocked */ }
  if (saved === 'light' || saved === 'dark') document.documentElement.setAttribute('data-theme', saved);
})();
