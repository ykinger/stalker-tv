FROM python:3.13-slim
WORKDIR /srv
COPY tools/mock-portal/server.py /srv/server.py
COPY app/src/main/assets/big_buck_bunny_sample.mp4 /media/sample.mp4
USER 65534:65534
EXPOSE 8080
CMD ["python", "-u", "/srv/server.py"]
