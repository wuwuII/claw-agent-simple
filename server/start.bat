@echo off
chcp 65001 >nul
echo ==========================================
echo    Claw Voice Server - 语音对话服务端
echo ==========================================
echo.

REM 检查Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到Python，请安装Python 3.10+
    pause
    exit /b 1
)

REM 检查虚拟环境
if not exist venv (
    echo [信息] 创建虚拟环境...
    python -m venv venv
)

echo [信息] 激活虚拟环境...
call venv\Scripts\activate.bat

REM 检查依赖
if not exist venv\Lib\site-packages\websockets (
    echo [信息] 安装依赖...
    pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
)

REM 检查.env文件
if not exist .env (
    echo [警告] 未找到.env文件，使用默认配置
    echo [提示] 请复制.env.example为.env并配置KIMI_API_KEY
)

echo.
echo [信息] 启动服务端...
echo.

python voice_server_text_only.py

pause
