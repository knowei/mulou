"""判断站点到底是"死了"还是"被网络掐了"：DNS → TCP443 → TLS 握手 → HTTP 应答。

用法：python tools/host-check.py www.mitaodm.com vdm10.com ...
"""

import socket
import ssl
import sys

TIMEOUT = 6

print("openssl=%s" % ssl.OPENSSL_VERSION)
for host in sys.argv[1:]:
    row = [host]
    try:
        infos = socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)
        ips = sorted({i[4][0] for i in infos})
        row.append("dns=ok(%s)" % ",".join(ips[:3]))
    except Exception as e:
        row.append("dns=fail(%s)" % type(e).__name__)
        print("  ".join(row))
        continue
    # TCP
    try:
        sock = socket.create_connection((host, 443), timeout=TIMEOUT)
        row.append("tcp=ok")
    except Exception as e:
        row.append("tcp=fail(%s)" % type(e).__name__)
        print("  ".join(row))
        continue
    # TLS
    try:
        ctx = ssl.create_default_context()
        tls = ctx.wrap_socket(sock, server_hostname=host)
        row.append("tls=%s/%s" % (tls.version(), (tls.cipher() or ("?",))[0]))
        sock = tls
    except Exception as e:
        row.append("tls=fail(%s:%s)" % (type(e).__name__, str(e)[:40]))
        try:
            sock.close()
        except Exception:
            pass
        print("  ".join(row))
        continue
    # HTTP
    try:
        req = "GET / HTTP/1.1\r\nHost: %s\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n" % host
        sock.sendall(req.encode())
        data = sock.recv(200)
        first = data.split(b"\r\n")[0].decode("latin-1", "replace")
        row.append("http=%s" % first)
    except Exception as e:
        row.append("http=fail(%s)" % type(e).__name__)
    finally:
        try:
            sock.close()
        except Exception:
            pass
    print("  ".join(row))
