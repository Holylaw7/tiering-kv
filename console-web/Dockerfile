# 控制台前端（Vue 3 + Vite）静态构建 + Nginx 托管
FROM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json* ./
RUN npm ci || npm install
COPY . .
RUN npm run build

FROM alpine:3.24
RUN apk upgrade --no-cache \
    && apk add --no-cache nginx \
    && rm -rf /etc/nginx/http.d \
    && if ! awk -F: '$3 == 101 { found=1 } END { exit !found }' /etc/group; then addgroup -g 101 -S appnginx; fi \
    && if ! awk -F: '$3 == 101 { found=1 } END { exit !found }' /etc/passwd; then \
         adduser -u 101 -S -D -H -G "$(awk -F: '$3 == 101 { print $1; exit }' /etc/group)" appnginx; \
       fi \
    && mkdir -p /etc/nginx/conf.d /var/cache/nginx /var/log/nginx /usr/share/nginx/html \
    && ln -sf /dev/stdout /var/log/nginx/access.log \
    && ln -sf /dev/stderr /var/log/nginx/error.log \
    && printf '%s\n' \
       'worker_processes auto;' \
       'pid /tmp/nginx.pid;' \
       'error_log /dev/stderr notice;' \
       'events { worker_connections 1024; }' \
       'http {' \
       '    include /etc/nginx/mime.types;' \
       '    default_type application/octet-stream;' \
       '    access_log /dev/stdout;' \
       '    sendfile on;' \
       '    keepalive_timeout 65;' \
       '    client_body_temp_path /tmp/client_temp;' \
       '    proxy_temp_path /tmp/proxy_temp;' \
       '    fastcgi_temp_path /tmp/fastcgi_temp;' \
       '    uwsgi_temp_path /tmp/uwsgi_temp;' \
       '    scgi_temp_path /tmp/scgi_temp;' \
       '    include /etc/nginx/conf.d/*.conf;' \
       '}' > /etc/nginx/nginx.conf \
    && chown -R 101:101 /etc/nginx/conf.d /var/cache/nginx /var/log/nginx /usr/share/nginx/html
USER 101:101
RUN nginx -e /dev/stderr -t && rm -rf /tmp/nginx.pid /tmp/*_temp
COPY --from=build --chown=101:101 /build/dist /usr/share/nginx/html
COPY --chown=101:101 nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null 2>&1 || exit 1
CMD ["nginx", "-e", "/dev/stderr", "-g", "daemon off;"]
