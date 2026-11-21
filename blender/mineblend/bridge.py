"""与 MineBlend mod 通信（纯标准库，不依赖 bpy）。"""
import json
import struct
import urllib.error
import urllib.parse
import urllib.request


class BridgeError(Exception):
    pass


# 不走系统代理，避免 127.0.0.1 被代理软件拦截
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def _request(host, port, path, params=None, timeout=10.0):
    url = f"http://{host}:{port}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    try:
        with _OPENER.open(url, timeout=timeout) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        try:
            msg = json.loads(e.read().decode("utf-8")).get("error", str(e))
        except Exception:
            msg = str(e)
        raise BridgeError(msg) from None
    except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as e:
        raise BridgeError(
            "无法连接 MC：请确认游戏已启动、已进入存档，且安装了 MineBlend mod"
        ) from None


def get_json(host, port, path, params=None, timeout=10.0):
    data = _request(host, port, path, params, timeout)
    obj = json.loads(data.decode("utf-8"))
    if not obj.get("ok", False):
        raise BridgeError(obj.get("error", "未知错误"))
    return obj


class Packet:
    """二进制响应：int32 头长度 + JSON 头 + 数据段（偏移见 header['sections']）"""

    def __init__(self, data: bytes):
        if len(data) < 4:
            raise BridgeError("返回数据为空")
        (hlen,) = struct.unpack_from("<i", data, 0)
        self.header = json.loads(data[4:4 + hlen].decode("utf-8"))
        if not self.header.get("ok", False):
            raise BridgeError(self.header.get("error", "未知错误"))
        self.data = data
        self.base = 4 + hlen

    def section(self, name):
        off, length = self.header["sections"][name]
        return self.base + off, length

    def blob(self, offset, length):
        return self.data[self.base + offset:self.base + offset + length]


def get_packet(host, port, path, params=None, timeout=900.0):
    return Packet(_request(host, port, path, params, timeout))
