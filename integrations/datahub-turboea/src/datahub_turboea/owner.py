"""Version 1 owner transport. No model API, database driver or second registry writer."""
from dataclasses import dataclass
import http.client
import json
import unicodedata
from urllib.parse import urlsplit
import uuid


class OwnerError(RuntimeError):
    def __init__(self,status,code):
        self.status,self.code = status,code
        super().__init__(f"Federation owner request failed: HTTP {status} {code}")


def text(value,limit):
    if not isinstance(value,str) or not value or value != value.strip() or any(unicodedata.category(c) == "Cc" for c in value):
        raise ValueError("Invalid federation identifier")
    if len(value.encode("utf-8")) > limit: raise ValueError("Federation identifier exceeds byte limit")
    return value


@dataclass(frozen=True)
class SourceRef:
    source_key: str
    native_id: str
    external_type: str

    def __post_init__(self):
        text(self.source_key,128); text(self.native_id,8192); text(self.external_type,128)

    def document(self):
        return {"sourceKey":self.source_key,"nativeId":self.native_id,"externalType":self.external_type}


class OwnerClient:
    def __init__(self,base_url,headers,*,bootstrap=False,timeout=30,max_bytes=2_097_152):
        self.base = urlsplit(base_url)
        if self.base.username or self.base.password or self.base.query or self.base.fragment or not self.base.hostname:
            raise ValueError("Invalid federation owner URL")
        local = self.base.hostname in ("127.0.0.1","localhost","::1")
        if self.base.scheme != "https" and not (self.base.scheme == "http" and local):
            raise ValueError("Federation owner requires HTTPS or loopback HTTP")
        if bootstrap and not local: raise ValueError("Bootstrap headers are restricted to a local demo")
        if not 0 < timeout <= 120 or not 1024 <= max_bytes <= 16_777_216: raise ValueError("Invalid owner transport bounds")
        self.headers,self.bootstrap,self.timeout,self.max_bytes = headers,bootstrap,timeout,max_bytes

    def lookup(self,source): return self._request("identities/lookup",source.document())
    def observe(self,source): return self._request("sources/observe",source.document())
    def reserve_source(self,source,correlation):
        if str(uuid.UUID(correlation)) != correlation.lower(): raise ValueError("Canonical UUID correlation required")
        return self._request("sources/reserve",{"source":source.document(),"correlation":correlation})

    def _request(self,path,payload):
        supplied = self.headers()
        allowed = {"X-Archimesh-User","X-Archimesh-Roles"} if self.bootstrap else {"Authorization"}
        if set(supplied) != allowed or any(not isinstance(v,str) or not v or any(ord(c)<32 or ord(c)>126 for c in v) for v in supplied.values()):
            raise ValueError("Invalid owner authentication headers")
        if not self.bootstrap and not supplied["Authorization"].startswith("Bearer "): raise ValueError("Owner bearer authentication required")
        connection_type = http.client.HTTPSConnection if self.base.scheme == "https" else http.client.HTTPConnection
        connection = connection_type(self.base.hostname,self.base.port,timeout=self.timeout)
        try:
            connection.request("POST",self.base.path.rstrip("/") + "/federation/v1/" + path,
                json.dumps(payload,ensure_ascii=False).encode(),{"Accept":"application/json","Content-Type":"application/json",**supplied})
            response = connection.getresponse()
            raw = response.read(self.max_bytes+1)
            if len(raw) > self.max_bytes: raise OwnerError(502,"OWNER_RESPONSE_TOO_LARGE")
            try: value = json.loads(raw)
            except (ValueError,RecursionError): raise OwnerError(502,"INVALID_OWNER_RESPONSE") from None
            if not isinstance(value,dict): raise OwnerError(502,"INVALID_OWNER_RESPONSE")
            if response.status != 200:
                code = value.get("code","")
                if not isinstance(code,str) or not code or len(code)>100 or any(c not in "ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789" for c in code): code="OWNER_HTTP_ERROR"
                raise OwnerError(response.status,code)
            return value
        except (OSError,http.client.HTTPException):
            # The command is deliberately not retried. Retain the same correlation for recovery.
            raise OwnerError(503,"OWNER_UNAVAILABLE") from None
        finally: connection.close()
