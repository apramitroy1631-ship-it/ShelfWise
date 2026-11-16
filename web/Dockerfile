# Web app image (deployed). Build context: web/
# Stage 1 builds the static app; stage 2 serves it with nginx, which also forwards /api calls to the two services
# over the platform's private network. The browser sees ONE address for everything (see nginx.conf.template).

FROM node:26-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
# The official image fills ${...} in templates from the environment at start-up (only variables that are set).
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# Defaults match docker-compose service names; Railway sets these to its private addresses (docs/deploy.md).
ENV PORT=8080 \
    CORE_URL=http://core-service:8080 \
    NOTIFICATIONS_URL=http://notification-service:8081
EXPOSE 8080
