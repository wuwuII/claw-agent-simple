"""
Claw Voice Server 配置文件
"""
import os
from dotenv import load_dotenv

load_dotenv()

# 服务器配置
HOST = os.getenv("HOST", "0.0.0.0")
PORT = int(os.getenv("PORT", "8765"))

# Kimi API配置
KIMI_API_KEY = os.getenv("KIMI_API_KEY", "")
KIMI_BASE_URL = os.getenv("KIMI_BASE_URL", "https://api.moonshot.cn/v1")

# 可用模型列表（供Android端选择）
AVAILABLE_MODELS = [
    "kimi-k2.5",           # 默认，支持多模态+Function Calling
    "kimi-latest",         # 最新版本
    "moonshot-v1-8k",      # 轻量版
    "moonshot-v1-32k",     # 长文本
    "moonshot-v1-128k",    # 超长文本
]

# 默认模型
DEFAULT_MODEL = os.getenv("DEFAULT_MODEL", "kimi-k2.5")
KIMI_MODEL = os.getenv("KIMI_MODEL", DEFAULT_MODEL)

# 语音配置
ASR_MODEL = os.getenv("ASR_MODEL", "paraformer-zh")  # FunASR模型
TTS_MODEL = os.getenv("TTS_MODEL", "paddlespeech")   # TTS模型
TTS_SPEAKER = os.getenv("TTS_SPEAKER", "default")    # 说话人

# VAD配置
VAD_THRESHOLD = float(os.getenv("VAD_THRESHOLD", "0.5"))
VAD_MIN_SILENCE_DURATION = float(os.getenv("VAD_MIN_SILENCE_DURATION", "0.5"))

# 对话配置
MAX_CONVERSATION_HISTORY = int(os.getenv("MAX_CONVERSATION_HISTORY", "10"))
SHORT_REPLY_MODE = os.getenv("SHORT_REPLY_MODE", "true").lower() == "true"

# 日志配置
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")
