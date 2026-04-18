"""
简化版WebSocket服务端 - 支持文字/图片聊天，后端模型可配置
默认模型：kimi-k2.5（支持多模态）
"""
import asyncio
import json
import base64
import time
from typing import Dict, Optional
from dataclasses import dataclass, field

import websockets
import logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(message)s')
logger = logging.getLogger(__name__)

import sys
sys.path.insert(0, '.')

# ──────────────────────────────────────────────────────────────
# 可用模型列表（客户端可通过 get_models 查询）
# ──────────────────────────────────────────────────────────────
AVAILABLE_MODELS = [
    {
        "id": "kimi-k2.5",
        "name": "Kimi K2.5",
        "description": "Moonshot 旗舰模型，支持图像理解",
        "multimodal": True
    },
    {
        "id": "moonshot-v1-8k",
        "name": "Moonshot v1 8K",
        "description": "Moonshot 快速模型，8K 上下文",
        "multimodal": False
    },
    {
        "id": "moonshot-v1-32k",
        "name": "Moonshot v1 32K",
        "description": "Moonshot 均衡模型，32K 上下文",
        "multimodal": False
    }
]

DEFAULT_MODEL_ID = "kimi-k2.5"


@dataclass
class Session:
    """用户会话"""
    device_id: str
    websocket: any
    model_id: str = DEFAULT_MODEL_ID
    last_activity: float = field(default_factory=time.time)


class LLMEngine:
    """大语言模型引擎 - Kimi (Moonshot API)"""

    KIMI_API_KEY = "sk-jdLkpkBpSeSWHsyuq3lBW7z5N4ikIH87x2BdTKFO73j07z33"
    KIMI_BASE_URL = "https://api.moonshot.cn/v1"

    def __init__(self):
        logger.info("LLM 引擎初始化 (Kimi API)")

    def _get_model_info(self, model_id: str) -> dict:
        for m in AVAILABLE_MODELS:
            if m["id"] == model_id:
                return m
        # 找不到则用默认
        return AVAILABLE_MODELS[0]

    async def chat(self, message: str, model_id: str = DEFAULT_MODEL_ID,
                   image_data: bytes = None) -> str:
        """对话生成（支持多模态）"""
        try:
            import aiohttp

            model_info = self._get_model_info(model_id)

            headers = {
                "Authorization": f"Bearer {self.KIMI_API_KEY}",
                "Content-Type": "application/json; charset=utf-8"
            }

            # 构造消息体
            if image_data and model_info.get("multimodal"):
                image_b64 = base64.b64encode(image_data).decode("utf-8")
                user_content = [
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/jpeg;base64,{image_b64}"
                        }
                    },
                    {"type": "text", "text": message or "描述这张图片"}
                ]
            elif image_data and not model_info.get("multimodal"):
                # 当前模型不支持图片，降级为纯文字
                logger.warning(f"模型 {model_id} 不支持图像，降级为纯文字")
                user_content = message or "描述这张图片"
            else:
                user_content = message

            messages = [{"role": "user", "content": user_content}]

            # kimi-k2.5 是 thinking 模型，temperature 必须为 1
            temperature = 1 if model_id == "kimi-k2.5" else 0.7

            payload = {
                "model": model_id,
                "messages": messages,
                "temperature": temperature
            }

            logger.info(f"调用 LLM: model={model_id}, multimodal={image_data is not None}")

            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{self.KIMI_BASE_URL}/chat/completions",
                    headers=headers,
                    json=payload,
                    timeout=aiohttp.ClientTimeout(total=60)
                ) as response:
                    if response.status == 200:
                        result = await response.json()
                        msg = result["choices"][0]["message"]
                        # kimi-k2.5 thinking 模型：答案可能在 reasoning_content
                        content = msg.get("content") or msg.get("reasoning_content", "")
                        return content.strip() if content else "（模型未返回内容）"
                    else:
                        error_text = await response.text()
                        logger.error(f"LLM API 错误 {response.status}: {error_text}")
                        return f"抱歉，服务暂时不可用 (错误码: {response.status})"

        except Exception as e:
            logger.error(f"LLM 调用失败: {e}", exc_info=True)
            return f"抱歉，处理您的消息时出错了: {str(e)}"


class VoiceChatServer:
    """语音/文字对话服务器"""

    def __init__(self, host: str = "0.0.0.0", port: int = 8765):
        self.host = host
        self.port = port
        self.sessions: Dict[str, Session] = {}
        self.llm = LLMEngine()

    async def handle_client(self, websocket):
        """处理客户端连接"""
        client_addr = websocket.remote_address
        logger.info(f"客户端连接: {client_addr}")

        heartbeat_task = asyncio.create_task(self._heartbeat(websocket))

        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                    await self._handle_message(websocket, data)
                except json.JSONDecodeError:
                    await self._send_error(websocket, "无效的 JSON 格式")
                except Exception as e:
                    logger.error(f"处理消息失败: {e}", exc_info=True)
                    await self._send_error(websocket, f"服务器内部错误: {str(e)}")

        except websockets.exceptions.ConnectionClosed:
            logger.info(f"客户端断开: {client_addr}")
        finally:
            heartbeat_task.cancel()
            try:
                await heartbeat_task
            except asyncio.CancelledError:
                pass
            for device_id, session in list(self.sessions.items()):
                if session.websocket == websocket:
                    del self.sessions[device_id]
                    logger.info(f"会话已清理: {device_id}")
                    break

    async def _heartbeat(self, websocket):
        """每 30 秒发送心跳"""
        try:
            while True:
                await asyncio.sleep(30)
                if websocket.open:
                    await websocket.send(json.dumps({"type": "ping"}))
        except asyncio.CancelledError:
            pass
        except Exception as e:
            logger.error(f"心跳错误: {e}")

    def _get_session(self, websocket, device_id: Optional[str]) -> Session:
        """获取或查找会话，未注册时返回临时会话"""
        if device_id and device_id in self.sessions:
            return self.sessions[device_id]
        # 通过 websocket 反查
        for sid, session in self.sessions.items():
            if session.websocket == websocket:
                return session
        # 返回临时会话（不加入 self.sessions）
        return Session(device_id or "unknown", websocket)

    async def _handle_message(self, websocket, data: dict):
        """路由消息类型"""
        msg_type = data.get("type")
        device_id = data.get("device_id")

        # ── register ──────────────────────────────────────────
        if msg_type == "register":
            if not device_id:
                device_id = f"anon_{int(time.time())}"
            self.sessions[device_id] = Session(device_id, websocket)
            logger.info(f"设备注册: {device_id}")
            await websocket.send(json.dumps({
                "type": "registered",
                "message": "连接成功",
                "server_time": time.time(),
                "default_model": DEFAULT_MODEL_ID
            }))

        # ── get_models ────────────────────────────────────────
        elif msg_type == "get_models":
            session = self._get_session(websocket, device_id)
            await websocket.send(json.dumps({
                "type": "models_list",
                "models": AVAILABLE_MODELS,
                "current_model": session.model_id
            }))
            logger.info(f"[{session.device_id}] 查询模型列表")

        # ── set_model ─────────────────────────────────────────
        elif msg_type == "set_model":
            new_model_id = data.get("model_id", DEFAULT_MODEL_ID)
            valid_ids = [m["id"] for m in AVAILABLE_MODELS]
            if new_model_id not in valid_ids:
                await self._send_error(websocket,
                    f"未知模型: {new_model_id}，可用: {', '.join(valid_ids)}")
                return

            session = self._get_session(websocket, device_id)
            session.model_id = new_model_id
            logger.info(f"[{session.device_id}] 切换模型 → {new_model_id}")
            await websocket.send(json.dumps({
                "type": "model_set",
                "model_id": new_model_id,
                "message": f"已切换到 {new_model_id}"
            }))

        # ── text_message ──────────────────────────────────────
        elif msg_type == "text_message":
            text = data.get("text", "")
            session = self._get_session(websocket, device_id)
            logger.info(f"文字消息 [{session.device_id}] model={session.model_id}: {text[:80]}")

            await websocket.send(json.dumps({"type": "processing"}))

            try:
                response = await asyncio.wait_for(
                    self.llm.chat(text, model_id=session.model_id),
                    timeout=60
                )
                await websocket.send(json.dumps({
                    "type": "text_response",
                    "text": response,
                    "timestamp": time.time()
                }))
                logger.info(f"回复已发送: {response[:60]}...")
            except asyncio.TimeoutError:
                logger.error("LLM 调用超时")
                await websocket.send(json.dumps({
                    "type": "error",
                    "message": "LLM 响应超时，请重试"
                }))
            except Exception as e:
                logger.error(f"处理文字消息失败: {e}", exc_info=True)
                await websocket.send(json.dumps({
                    "type": "error",
                    "message": f"处理失败: {str(e)}"
                }))

        # ── image_message ─────────────────────────────────────
        elif msg_type == "image_message":
            text = data.get("text", "") or "描述这张图片"
            image_b64 = data.get("image", "")
            session = self._get_session(websocket, device_id)
            logger.info(f"图片消息 [{session.device_id}] model={session.model_id}")

            await websocket.send(json.dumps({"type": "processing"}))

            try:
                image_data = base64.b64decode(image_b64) if image_b64 else None
                response = await asyncio.wait_for(
                    self.llm.chat(text, model_id=session.model_id, image_data=image_data),
                    timeout=90
                )
                await websocket.send(json.dumps({
                    "type": "text_response",
                    "text": response,
                    "timestamp": time.time()
                }))
                logger.info(f"图片回复已发送: {response[:60]}...")
            except asyncio.TimeoutError:
                logger.error("图片 LLM 调用超时")
                await websocket.send(json.dumps({
                    "type": "error",
                    "message": "处理图片超时，请重试"
                }))
            except Exception as e:
                logger.error(f"处理图片消息失败: {e}", exc_info=True)
                await websocket.send(json.dumps({
                    "type": "error",
                    "message": f"图片处理失败: {str(e)}"
                }))

        # ── ping ──────────────────────────────────────────────
        elif msg_type == "ping":
            await websocket.send(json.dumps({"type": "pong"}))

        else:
            await self._send_error(websocket, f"未知消息类型: {msg_type}")

    async def _send_error(self, websocket, message: str):
        await websocket.send(json.dumps({
            "type": "error",
            "message": message
        }))

    async def start(self):
        """启动服务器"""
        logger.info(f"启动 WebSocket 服务器: {self.host}:{self.port}")
        logger.info(f"默认模型: {DEFAULT_MODEL_ID}")
        async with websockets.serve(self.handle_client, self.host, self.port):
            logger.info(f"服务器已就绪: ws://{self.host}:{self.port}")
            await asyncio.Future()


if __name__ == "__main__":
    server = VoiceChatServer()
    asyncio.run(server.start())
