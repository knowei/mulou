"""看站点证书到底是什么情况：是自签、过期，还是只是本机 certifi 太旧。

用法：python tools/cert-check.py www.mcydh.com AGE ...（可带路径，如 www.agedm.io/search?wd=x）
"""

import socket
import ssl
import sys

TIMEOUT = 8

for target in sys.argv[1:]:
    host = target.split("/")[0].split("?")[0]
    print("== %s" % host)
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with socket.create_connection((host, 443), timeout=TIMEOUT) as sock:
            with ctx.wrap_socket(sock, server_hostname=host) as tls:
                der = tls.getpeercert(binary_form=True)
                print("   tls=%s" % tls.version())
    except Exception as e:
        print("   connect-fail %s: %s" % (type(e).__name__, str(e)[:60]))
        continue
    # 用 cryptography 解析证书（不是所有环境都有，没有就跳过）
    try:
        from cryptography import x509
        from cryptography.hazmat.backends import default_backend

        cert = x509.load_der_x509_certificate(der, default_backend())
        print("   subject=%s" % cert.subject.rfc4514_string())
        print("   issuer =%s" % cert.issuer.rfc4514_string())
        print("   notAfter=%s" % cert.not_valid_after)
        try:
            san = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName)
            print("   san   =%s" % ", ".join(san.value.get_values_for_type(x509.DNSName))[:120])
        except Exception:
            print("   san   =(none)")
    except ImportError:
        print("   (no cryptography)")
