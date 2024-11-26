#!/bin/bash

# 检查端口是否被占用
check_port() {
    local port=$1
    if lsof -i:$port > /dev/null 2>&1; then
        return 1
    else
        return 0
    fi
}

# 默认端口
PORT=5000

# 检查并尝试其他端口
while ! check_port $PORT; do
    echo "端口 $PORT 已被占用，尝试下一个端口..."
    PORT=$((PORT + 1))
    if [ $PORT -gt 6000 ]; then
        echo "错误：无法找到可用端口"
        exit 1
    fi
done

echo "使用端口: $PORT"
java -jar target/bootstrap-node.jar --port=$PORT 