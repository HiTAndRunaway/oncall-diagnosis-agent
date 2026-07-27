// SuperBizAgent 登录页面
document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('loginForm');
    const input = document.getElementById('apiKeyInput');
    const errorEl = document.getElementById('loginError');
    const submitBtn = document.getElementById('loginSubmitBtn');

    // 自动聚焦输入框
    input.focus();

    form.addEventListener('submit', async (e) => {
        e.preventDefault();

        const apiKey = input.value.trim();

        if (!apiKey) {
            showError('请输入 API Key');
            return;
        }

        // 禁用按钮，显示加载状态
        submitBtn.disabled = true;
        submitBtn.innerHTML = '<span>验证中...</span>';
        errorEl.style.display = 'none';

        try {
            const response = await fetch('/api/login', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ apiKey: apiKey })
            });

            const data = await response.json();

            if (response.ok && data.code === 200) {
                // 登录成功，保存 API Key
                localStorage.setItem('sbiz_api_key', apiKey);

                // 保存用户描述信息（如果有）
                if (data.data && data.data.description) {
                    localStorage.setItem('sbiz_user_description', data.data.description);
                }
                if (data.data && data.data.userId) {
                    localStorage.setItem('sbiz_user_description', data.data.description || data.data.userId);
                }

                // 跳转到控制台
                window.location.href = '/index.html';
            } else {
                showError(data.message || '无效的 API Key');
            }
        } catch (error) {
            console.error('登录请求失败:', error);
            showError('网络错误，请检查连接后重试');
        } finally {
            submitBtn.disabled = false;
            submitBtn.innerHTML = '<span>进入控制台</span>';
        }
    });

    function showError(message) {
        errorEl.textContent = message;
        errorEl.style.display = 'block';
    }
});
