FROM mysql:sha256:fe036967257bf11aab7184e371920c5b629f0dd36cbefdf4ccd2ae18cc900dbd

ENV MYSQL_DATABASE=cities \
    MYSQL_USER=shipping \
    MYSQL_ROOT_PASSWORD=dynosoar \
    MYSQL_PASSWORD=secret

# Adjust my.cnf if needed
COPY config.sh /root/
RUN /root/config.sh

# Initialization scripts (SQL or .sh)
COPY scripts/* /docker-entrypoint-initdb.d/
