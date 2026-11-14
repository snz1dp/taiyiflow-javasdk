#!/bin/sh
# vim:sw=4:ts=4:et
# 运行时上下文路径重定位：
# 构建时 VitePress base 固定为 /taiyi/javasdk/docs/，
# 启动时按 WEB_CONTEXT_PATH 环境变量替换静态产物与 nginx 配置中的路径前缀。

set -e

DEFAULT_CONTEXT_PATH="/taiyi/javasdk/docs"

if [ -z "${WEB_CONTEXT_PATH}" ]; then
    export WEB_CONTEXT_PATH="${DEFAULT_CONTEXT_PATH}"
fi

# 去除尾部斜杠，保证替换结果一致
WEB_CONTEXT_PATH="${WEB_CONTEXT_PATH%/}"

if [ "${WEB_CONTEXT_PATH}" != "${DEFAULT_CONTEXT_PATH}" ]; then
    # 替换静态产物中的 base 路径（html/js/css/json 等文本文件）
    # 使用 -i.bak 兼容 GNU/BSD/BusyBox sed，随后清理备份文件
    find /app/html -type f \( -name '*.html' -o -name '*.js' -o -name '*.css' -o -name '*.json' -o -name '*.svg' -o -name '*.txt' -o -name '*.xml' \) \
        -exec sed -i.bak "s|${DEFAULT_CONTEXT_PATH}|${WEB_CONTEXT_PATH}|g" {} +
    find /app/html -type f -name '*.bak' -delete

    # 同步替换 nginx 配置中的 404 回退路径
    sed -i.bak "s|${DEFAULT_CONTEXT_PATH}|${WEB_CONTEXT_PATH}|g" /etc/nginx/conf.d/docs.conf
    rm -f /etc/nginx/conf.d/docs.conf.bak

    echo "web context replaced: ${DEFAULT_CONTEXT_PATH} -> ${WEB_CONTEXT_PATH}"
else
    echo "web context unchanged: ${WEB_CONTEXT_PATH}"
fi