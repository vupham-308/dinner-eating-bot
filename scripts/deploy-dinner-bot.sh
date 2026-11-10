#!/usr/bin/env bash
# Tạo lại container dinner-bot. Biến môi trường được truyền vào từ GitHub Actions (ssh-action envs).
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/app/dinner-bot}"
CONTAINER="dinner-bot"
IMAGE="eclipse-temurin:17-jre"
JAR="$APP_DIR/dinner-bot.jar"
PORT="${PORT:-6970}"

: "${BOT_TOKEN:?Missing BOT_TOKEN}"
: "${DB_URL:?Missing DB_URL}"
: "${DB_USER:?Missing DB_USER}"
: "${DB_PASSWORD:?Missing DB_PASSWORD}"

[ -f "$JAR" ] || { echo "Không tìm thấy $JAR" >&2; exit 1; }

# Dùng sudo nếu user hiện tại không có quyền docker
DOCKER="docker"
if ! docker info >/dev/null 2>&1; then DOCKER="sudo docker"; fi

# Ghi env ra file tạm (quyền 600), xoá ngay sau khi tạo container -> không để secret lại trên ổ đĩa
ENV_FILE="$(mktemp)"
chmod 600 "$ENV_FILE"
trap 'rm -f "$ENV_FILE"' EXIT
{
  echo "BOT_TOKEN=${BOT_TOKEN}"
  echo "DB_URL=${DB_URL}"
  echo "DB_USER=${DB_USER}"
  echo "DB_PASSWORD=${DB_PASSWORD}"
  # Chỉ dùng khi bảng members còn trống (lần chạy đầu)
  if [ -n "${BOT_MEMBERS:-}" ]; then echo "BOT_MEMBERS=${BOT_MEMBERS}"; fi
  echo "PORT=${PORT}"
  echo "TZ=Asia/Ho_Chi_Minh"
  # JVM tự nhận giới hạn CPU/RAM của container; heap tối đa 256m (container giới hạn 384m)
  echo "JAVA_TOOL_OPTIONS=-Xms64m -Xmx256m -XX:+UseSerialGC -Duser.timezone=Asia/Ho_Chi_Minh"
} > "$ENV_FILE"

echo "- Pull image $IMAGE"
$DOCKER pull -q "$IMAGE" >/dev/null

echo "- Dừng container cũ (nếu có)"
$DOCKER rm -f "$CONTAINER" >/dev/null 2>&1 || true

echo "- Chạy container mới"
# --network host: app lắng nghe trực tiếp cổng $PORT (6970) trên VPS,
# và DB_URL dạng localhost:1433 trỏ đúng SQL Server chạy trên VPS
$DOCKER run -d \
  --name "$CONTAINER" \
  --restart unless-stopped \
  --network host \
  --cpus 0.5 \
  --memory 384m \
  --env-file "$ENV_FILE" \
  -v "$JAR:/app/app.jar:ro" \
  --log-opt max-size=10m --log-opt max-file=3 \
  "$IMAGE" \
  java -jar /app/app.jar >/dev/null

# Khởi động với 0.5 CPU chậm hơn bình thường -> chờ tối đa 150s
echo "- Kiểm tra khởi động (tối đa 150s)..."
for i in $(seq 1 50); do
  sleep 3
  if [ "$($DOCKER inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" != "true" ]; then
    echo "Container đã dừng — log:" >&2
    $DOCKER logs --tail 100 "$CONTAINER" >&2 || true
    exit 1
  fi
  if curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1 \
     && $DOCKER logs "$CONTAINER" 2>&1 | grep -q "Bot đã chạy"; then
    echo "✅ Bot đã chạy — health: http://127.0.0.1:${PORT}/health"
    $DOCKER logs --tail 20 "$CONTAINER"
    exit 0
  fi
done

echo "Quá 150s chưa thấy bot khởi động xong — log:" >&2
$DOCKER logs --tail 100 "$CONTAINER" >&2 || true
exit 1
