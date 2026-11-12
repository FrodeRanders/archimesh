import http.server
import json
from pathlib import Path
import sys
import threading
import unittest
import uuid

sys.path.insert(0,str(Path(__file__).resolve().parents[1] / "src"))
from datahub_turboea.owner import OwnerClient,OwnerError,SourceRef


class OwnerTest(unittest.TestCase):
    def test_exact_source_and_retained_correlation_only_no_client_evidence(self):
        calls = []
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                calls.append((self.path,json.loads(self.rfile.read(int(self.headers["Content-Length"])))))
                self.send_response(200); self.end_headers(); self.wfile.write(b'{"registered":true}')
            def log_message(self,*args): pass
        with http.server.ThreadingHTTPServer(("127.0.0.1",0),Handler) as server:
            worker = threading.Thread(target=server.serve_forever); worker.start()
            try:
                client = OwnerClient("http://127.0.0.1:" + str(server.server_port),lambda:{"Authorization":"Bearer synthetic"})
                source = SourceRef("catalog","urn:li:dataset:(urn:li:dataPlatform:postgres,Case.%2FOrders,DEV)","dataset")
                correlation = str(uuid.uuid4())
                client.observe(source); client.reserve_source(source,correlation); client.reserve_source(source,correlation)
                self.assertEqual(source.native_id,calls[0][1]["nativeId"])
                self.assertEqual(calls[1],calls[2]); self.assertEqual({"source","correlation"},set(calls[1][1]))
                self.assertTrue(all(path.startswith("/federation/v1/") for path,body in calls))
            finally: server.shutdown(); worker.join()

    def test_unsafe_urls_credentials_and_identifier_rewrites_are_rejected(self):
        for url in ("http://example.org","https://user:password@example.org","https://example.org/?token=secret"):
            with self.assertRaises(ValueError): OwnerClient(url,lambda:{})
        with self.assertRaises(ValueError): OwnerClient("https://example.org",lambda:{},bootstrap=True)
        for native in (" padded ","urn:with\ncontrol","x"*8193):
            with self.assertRaises(ValueError): SourceRef("catalog",native,"dataset")


if __name__ == "__main__": unittest.main()
