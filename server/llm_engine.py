"""
LLM对话引擎 - Kimi API
"""
import httpx
from typing import List, Dict, Any
from loguru import logger
import config


class LLMEngine:
    """Kimi LLM对话引擎"""
    
    def __init__(self):
        self.api_key = config.KIMI_API_KEY
        self.base_url = config.KIMI_BASE_URL.rstrip("/")
        self.model = config.KIMI_MODEL
        self.short_reply_mode = config.SHORT_REPLY_MODE
        
        if not self.api_key:
            logger.warning("KIMI_API_KEY未设置，LLM功能将不可用")
    
    async def chat(self, message: str, history: List[Dict[str, str]] = None) -> str:
        """
        对话
        
        Args:
            message: 用户消息
            history: 历史对话记录 [{"role": "user"/"assistant", "content": "..."}]
            
        Returns:
            AI回复
        """
        if not self.api_key:
            return "API密钥未配置，无法调用AI"
        
        try:
            # 构建消息
            messages = []
            
            # 系统提示词
            system_prompt = self._build_system_prompt()
            if system_prompt:
                messages.append({
                    "role": "system",
                    "content": system_prompt
                })
            
            # 历史对话
            if history:
                for item in history[-config.MAX_CONVERSATION_HISTORY:]:
                    messages.append({
                        "role": item["role"],
                        "content": item["content"]
                    })
            
            # 当前消息
            messages.append({
                "role": "user",
                "content": message
            })
            
            # 调用API
            async with httpx.AsyncClient(timeout=60.0) as client:
                response = await client.post(
                    f"{self.base_url}/chat/completions",
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json"
                    },
                    json={
                        "model": self.model,
                        "messages": messages,
                        "temperature": 1,
                        "max_tokens": 500 if self.short_reply_mode else 2000,
                        "stream": False
                    }
                )
                
                response.raise_for_status()
                data = response.json()
                
                # 提取回复（兼容thinking模型：content可能为空，答案在reasoning_content）
                if "choices" in data and len(data["choices"]) > 0:
                    content = data["choices"][0].get("message", {}).get("content", "")
                    reasoning = data["choices"][0].get("message", {}).get("reasoning_content", "")
                    return content.strip() if content and content.strip() else reasoning.strip()
                
                return "AI未返回有效回复"
                
        except httpx.HTTPError as e:
            logger.error(f"LLM API请求失败: {e}")
            return f"AI调用失败: {str(e)}"
        except Exception as e:
            logger.error(f"LLM对话失败: {e}")
            return f"AI对话出错: {str(e)}"
    
    async def chat_with_image(self, image_b64: str, mime_type: str, history: List[Dict[str, str]] = None) -> str:
        """
        多模态对话（图片+文字）
        
        Args:
            image_b64: 图片Base64编码
            mime_type: 图片MIME类型
            history: 历史对话记录
            
        Returns:
            AI回复
        """
        if not self.api_key:
            return "API密钥未配置，无法调用AI"
        
        try:
            # 构建消息
            messages = []
            
            # 系统提示词
            system_prompt = self._build_system_prompt()
            if system_prompt:
                messages.append({
                    "role": "system",
                    "content": system_prompt
                })
            
            # 历史对话
            if history:
                for item in history[-config.MAX_CONVERSATION_HISTORY:]:
                    messages.append({
                        "role": item["role"],
                        "content": item["content"]
                    })
            
            # 当前消息（包含图片）
            messages.append({
                "role": "user",
                "content": [
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:{mime_type};base64,{image_b64}"
                        }
                    },
                    {
                        "type": "text",
                        "text": "请描述这张图片"
                    }
                ]
            })
            
            # 调用API
            async with httpx.AsyncClient(timeout=60.0) as client:
                response = await client.post(
                    f"{self.base_url}/chat/completions",
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json"
                    },
                    json={
                        "model": self.model,
                        "messages": messages,
                        "temperature": 1,
                        "max_tokens": 500 if self.short_reply_mode else 2000,
                        "stream": False
                    }
                )
                
                response.raise_for_status()
                data = response.json()
                
                # 提取回复（兼容thinking模型）
                if "choices" in data and len(data["choices"]) > 0:
                    content = data["choices"][0].get("message", {}).get("content", "")
                    reasoning = data["choices"][0].get("message", {}).get("reasoning_content", "")
                    return content.strip() if content and content.strip() else reasoning.strip()
                
                return "AI未返回有效回复"
                
        except httpx.HTTPError as e:
            logger.error(f"LLM图片API请求失败: {e}")
            return f"AI图片识别失败: {str(e)}"
        except Exception as e:
            logger.error(f"LLM图片对话失败: {e}")
            return f"AI图片处理出错: {str(e)}"
    
    def _build_system_prompt(self) -> str:
        """构建系统提示词"""
        prompts = []
        
        if self.short_reply_mode:
            prompts.append("你是果冻，一个AI语音助手。请用简短自然的方式回答（50字以内），除非用户要求详细解释。")
        else:
            prompts.append("你是果冻，一个AI语音助手。请用友好自然的方式回答。")
        
        prompts.append("当前是通过语音对话，请保持回答简洁易懂。")
        
        return "\n".join(prompts)


# 单例模式
_llm_engine = None

def get_llm_engine() -> LLMEngine:
    """获取LLM引擎单例"""
    global _llm_engine
    if _llm_engine is None:
        _llm_engine = LLMEngine()
    return _llm_engine
