"""常驻的 PaddleOCR 进程。

从标准输入读图片路径，识别结果写到标准输出，用单独一行 ---END--- 结束。
库自己的日志改走标准错误，避免和正文混在一起。
"""

import contextlib
import io
import os
import sys

os.environ.setdefault("GLOG_minloglevel", "3")
os.environ.setdefault("FLAGS_minloglevel", "3")
os.environ.setdefault("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True")
# Windows CPU 上 oneDNN 跑 PP-OCRv6 会报 PIR 属性转换错误，改走普通 CPU 内核。
os.environ["PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT"] = "False"
os.environ["FLAGS_use_mkldnn"] = "0"

# 这台机器把 pip 包装到 PIP_TARGET，解释器默认不会去那里找模块。
pip_target = os.environ.get("PIP_TARGET")
if pip_target and pip_target not in sys.path:
    sys.path.insert(0, pip_target)

REAL_STDOUT = sys.stdout
sys.stdout = sys.stderr

ENGINE = None


def engine():
    global ENGINE
    if ENGINE is not None:
        return ENGINE
    from paddleocr import PaddleOCR

    try:
        ENGINE = PaddleOCR(
            lang="ch",
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            use_textline_orientation=False,
        )
    except Exception:
        ENGINE = PaddleOCR(lang="ch")
    return ENGINE


def lines_from(result):
    lines = []
    if result is None:
        return lines
    if hasattr(result, "__iter__") and not isinstance(result, (str, bytes, dict)):
        for item in result:
            lines.extend(lines_from_item(item))
        return lines
    lines.extend(lines_from_item(result))
    return lines


def lines_from_item(item):
    if item is None:
        return []
    rec_texts = getattr(item, "rec_texts", None)
    if not rec_texts:
        try:
            rec_texts = item["rec_texts"]
        except Exception:
            rec_texts = None
    if rec_texts:
        return [str(text) for text in rec_texts if text]
    payload = item
    if hasattr(item, "json"):
        try:
            payload = item.json
        except Exception:
            payload = item
    if isinstance(payload, dict):
        res = payload.get("res", payload)
        if isinstance(res, dict) and res.get("rec_texts"):
            return [str(text) for text in res["rec_texts"] if text]
    if isinstance(item, list):
        texts = []
        for line in item:
            if isinstance(line, list) and len(line) >= 2 and isinstance(line[1], (list, tuple)) and line[1]:
                texts.append(str(line[1][0]))
        return texts
    return []


def recognize(path):
    model = engine()
    sink = io.StringIO()
    with contextlib.redirect_stdout(sink):
        if hasattr(model, "predict"):
            result = model.predict(path)
        else:
            result = model.ocr(path, cls=True)
    return "\n".join(lines_from(result)).strip()


def reply(text):
    REAL_STDOUT.write(text)
    if text and not text.endswith("\n"):
        REAL_STDOUT.write("\n")
    REAL_STDOUT.write("---END---\n")
    REAL_STDOUT.flush()


def main():
    for raw in sys.stdin:
        path = raw.strip().strip('"')
        if not path:
            continue
        try:
            reply(recognize(path))
        except Exception as ex:
            reply("---ERROR--- " + str(ex))


if __name__ == "__main__":
    main()
