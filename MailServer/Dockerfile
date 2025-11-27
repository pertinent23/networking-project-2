FROM eclipse-temurin:17-jdk

RUN apt-get update && \
    apt-get install -y iproute2 iputils-ping dnsutils tcpdump && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY . /app
RUN javac *.java
CMD ["java", "MailServer", "uliege.be", "10"]
