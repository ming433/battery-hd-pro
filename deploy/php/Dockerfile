# DevUtils API 运行时镜像
# 基础镜像：registry.cn-hangzhou.aliyuncs.com/mingyang01/php83:v1 (Debian 12 + PHP 8.3 + php-fpm)
# 补充 Laravel 12 所需但基础镜像缺失的扩展：pdo_mysql / bcmath / opcache
FROM registry.cn-hangzhou.aliyuncs.com/mingyang01/php83:v1

ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Asia/Shanghai

# 编译扩展所需的依赖（zip/unzip 供 composer 使用）
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        $PHPIZE_DEPS \
        unzip \
        libzip-dev; \
    docker-php-ext-install -j"$(nproc)" pdo_mysql bcmath opcache zip; \
    docker-php-ext-enable opcache; \
    apt-get purge -y --auto-remove $PHPIZE_DEPS || true; \
    rm -rf /var/lib/apt/lists/* /tmp/pear; \
    php -v

# Composer
COPY --from=composer:2 /usr/bin/composer /usr/bin/composer

# 目录与权限
RUN set -eux; \
    mkdir -p /var/www/api; \
    mkdir -p /var/www/api/storage/framework/{cache/data,sessions,testing,views} \
             /var/www/api/storage/logs /var/www/api/bootstrap/cache; \
    chown -R www-data:www-data /var/www/api

WORKDIR /var/www/api

# php-fpm 进程池：2C / 小内存机器，限制并发
COPY www.conf /usr/local/etc/php-fpm.d/www.conf

EXPOSE 9000

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD php -r 'exit(function_exists("fsockopen") && @fsockopen("127.0.0.1",9000,$e,$s,2) ? 0 : 1);'

CMD ["php-fpm", "-F", "-R"]
