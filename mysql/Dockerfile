FROM mysql:8.0.34

ENV MYSQL_DATABASE=cities \
    MYSQL_USER=shipping \
    MYSQL_ROOT_PASSWORD=dynosoar \
    MYSQL_PASSWORD=secret

# Adjust my.cnf if needed
COPY config.sh /root/
RUN /root/config.sh

# Initialization scripts (SQL or .sh)
COPY scripts/* /docker-entrypoint-initdb.d/
