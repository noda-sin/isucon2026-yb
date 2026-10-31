FROM python:3.13.5-alpine3.22
RUN apk add --no-cache iproute2
COPY docker/local/netem/controller.py docker/local/netem/tcp_probe.py /opt/netem/
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["python3", "/opt/netem/controller.py"]
CMD ["serve"]
