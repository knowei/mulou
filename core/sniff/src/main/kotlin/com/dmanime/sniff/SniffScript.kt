package com.dmanime.sniff

/**
 * 注入到播放页的 JS。
 *
 * 职责只有一个：把「页面自己算出来的媒体地址」报告给宿主，不解析、不判断。
 * 之所以要 hook 五处，是因为真实站点的取流路径不统一：
 * - `fetch` / `XHR`：接口返回 m3u8 地址；
 * - `<video src>` / `<source>`：直接写进 DOM（可能是动态插入的）；
 * - `MediaSource.addSourceBuffer`：blob 方案，拿不到原始 URL，只能标记出来。
 */
object SniffScript {

    const val BRIDGE_NAME = "dmanimeSniff"

    val script: String = """
        (function () {
          if (window.__dmHooked) return;
          window.__dmHooked = true;

          function report(url, from) {
            try {
              if (!url || typeof url !== 'string') return;
              if (window.$BRIDGE_NAME && window.$BRIDGE_NAME.postMessage) {
                window.$BRIDGE_NAME.postMessage(JSON.stringify({url: url, from: from || ''}));
              }
            } catch (e) {}
          }

          var originalFetch = window.fetch;
          if (originalFetch) {
            window.fetch = function (input) {
              try {
                var u = (input && input.url) || input;
                if (typeof u === 'string') report(u, 'fetch');
              } catch (e) {}
              return originalFetch.apply(this, arguments);
            };
          }

          var originalOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) {
            try { if (typeof url === 'string') report(url, 'xhr'); } catch (e) {}
            return originalOpen.apply(this, arguments);
          };

          var originalAppend = Node.prototype.appendChild;
          Node.prototype.appendChild = function (node) {
            try {
              if (node && (node.tagName === 'VIDEO' || node.tagName === 'SOURCE') && node.src) {
                report(node.src, 'dom');
              }
            } catch (e) {}
            return originalAppend.apply(this, arguments);
          };

          if (window.MediaSource) {
            var originalAdd = MediaSource.prototype.addSourceBuffer;
            MediaSource.prototype.addSourceBuffer = function (mime) {
              report('mse:' + mime, 'mse');
              return originalAdd.apply(this, arguments);
            };
          }

          setInterval(function () {
            try {
              var nodes = document.querySelectorAll('video, source');
              for (var i = 0; i < nodes.length; i++) {
                if (nodes[i].src) report(nodes[i].src, 'scan');
              }
            } catch (e) {}
          }, 1000);
        })();
    """.trimIndent()
}
