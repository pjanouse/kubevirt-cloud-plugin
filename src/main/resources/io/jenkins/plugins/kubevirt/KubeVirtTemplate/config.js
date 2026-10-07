/* The MIT License — see source repository for full text. */
(function () {
    'use strict';

    function syncDiskSource(container, value) {
        if (!value) {
            value = 'containerDisk';
        }
        container.setAttribute('data-disk-source', value);
    }

    function bindDiskSourceSelect(select) {
        var container = select.closest('.disk-source-options');
        if (!container) {
            return;
        }

        if (select.dataset.kubevirtDiskSourceBound) {
            syncDiskSource(container, select.value);
            return;
        }
        select.dataset.kubevirtDiskSourceBound = 'true';

        syncDiskSource(container, select.value);
        select.addEventListener('change', function () {
            syncDiskSource(container, select.value);
        });

        var observer = new MutationObserver(function () {
            syncDiskSource(container, select.value);
            if (select.options.length > 0) {
                observer.disconnect();
            }
        });
        observer.observe(select, { childList: true, subtree: true });
    }

    if (window.Behaviour) {
        Behaviour.specify(
            'select[name$="diskSourceType"]',
            'kubevirtDiskSourceType',
            0,
            bindDiskSourceSelect
        );
    }
})();
