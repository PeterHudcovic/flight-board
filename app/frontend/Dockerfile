FROM node:24-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json vite.config.ts ./
COPY src/ src/
COPY public/ public/
RUN npm run build

FROM nginxinc/nginx-unprivileged:stable-alpine
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/static.conf /etc/nginx/flight-board-static.conf
COPY nginx/local.conf /etc/nginx/flight-board-local.conf
COPY --from=build /build/dist/ /usr/share/nginx/html/
USER 101:101
EXPOSE 8080
