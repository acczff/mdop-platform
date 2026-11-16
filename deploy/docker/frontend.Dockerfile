# syntax=docker/dockerfile:1

FROM nginx:1.27-alpine AS runtime

ARG DIST_DIR=tmp/release/candidate/frontend

ENV MDOP_BACKEND_UPSTREAM=backend:8081

COPY ${DIST_DIR}/ /usr/share/nginx/html/
COPY deploy/docker/nginx.conf.template /etc/nginx/templates/default.conf.template

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --retries=3 --start-period=10s \
    CMD wget -q -O /dev/null http://127.0.0.1:80/ || exit 1
