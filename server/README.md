# Claw Voice Server - 语音对话服务端

基于小智ESP32架构的语音对话服务端，支持FunASR语音识别、PaddleSpeech语音合成、Kimi LLM对话。

## 功能特性

- 🎤 **语音识别**: FunASR (本地部署，中文优化)
- 🗣️ **语音合成**: PaddleSpeech (本地部署，CPU友好)
- 🧠 **AI对话**: Kimi API (支持切换本地LLM)
- 🔊 **VAD检测**: Silero VAD (自动检测说话结束)
- 🌐 **WebSocket**: 实时双向通信
- 💾 **对话记忆**: 支持多轮对话上下文

## 系统要求

- Python 3.10+
- Windows/Linux/macOS
- 内存: 4GB+
- 磁盘: 2GB (模型文件)

## 安装

### 1. 克隆/下载项目

```bash
cd server
```

### 2. 创建虚拟环境

```bash
python -m venv venv

# Windows
venv\Scripts\activate

# Linux/Mac
source venv/bin/activate
```

### 3. 安装依赖

```bash
pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
```

### 4. 配置环境变量

复制 `.env.example` 为 `.env`，填写配置：

```bash
cp .env.example .env
```

编辑 `.env`：
```env
# 必填
KIMI_API_KEY=your_kimi_api_key_here

# 可选
HOST=0.0.0.0
PORT=8765
```

## 启动

### Windows

双击 `start.bat` 或命令行：
```bash
start.bat
```

### Linux/Mac

```bash
python websocket_server.py
```

## 项目结构

```
server/
├── config.py              # 配置文件
├── websocket_server.py    # WebSocket服务端主程序
├── asr_engine.py          # 语音识别引擎 (FunASR)
├── tts_engine.py          # 语音合成引擎 (PaddleSpeech)
├── llm_engine.py          # LLM对话引擎 (Kimi)
├── vad_engine.py          # VAD检测引擎 (Silero)
├── requirements.txt       # Python依赖
├── start.bat             # Windows启动脚本
├── .env.example          # 环境变量示例
└── README.md             # 本文件
```

## WebSocket协议

### 客户端 -> 服务端

| 消息类型 | 说明 |
|---------|------|
| `register` | 设备注册 |
| `wake_word_detected` | 唤醒词检测通知 |
| `start_recording` | 开始录音 |
| `audio_data` | 音频数据 (base64) |
| `stop_recording` | 停止录音 |
| `ping` | 心跳 |

### 服务端 -> 客户端

| 消息类型 | 说明 |
|---------|------|
| `registered` | 注册成功 |
| `wake_word_confirmed` | 唤醒确认 |
| `recording_started` | 录音已开始 |
| `asr_processing` | ASR处理中 |
| `asr_result` | ASR识别结果 |
| `llm_processing` | LLM思考中 |
| `llm_result` | LLM回复 |
| `tts_processing` | TTS合成中 |
| `tts_result` | TTS音频数据 (base64) |
| `error` | 错误信息 |
| `pong` | 心跳响应 |

## 流程示例

```
[唤醒词检测] -> start_recording -> audio_data -> stop_recording
                                                      ↓
[服务端处理] ASR -> LLM -> TTS
                      ↓
[返回结果] tts_result (音频数据)
```

## 模型下载

首次启动会自动下载模型文件：
- FunASR: ~500MB
- PaddleSpeech: ~100MB
- Silero VAD: ~1MB

## 常见问题

### 1. 模型下载慢

设置镜像：
```bash
# Linux/Mac
export HF_ENDPOINT=https://hf-mirror.com

# Windows
set HF_ENDPOINT=https://hf-mirror.com
```

### 2. 内存不足

FunASR需要约2GB内存，如果内存不足可以：
- 关闭其他程序
- 使用更小的ASR模型

### 3. 防火墙问题

确保端口8765开放：
```bash
# Windows
netsh advfirewall firewall add rule name="Claw Voice Server" dir=in action=allow protocol=tcp localport=8765
```

## 许可证

MIT License
