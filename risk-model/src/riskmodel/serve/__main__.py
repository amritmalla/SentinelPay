import uvicorn

from riskmodel.serve.app import app


def main() -> None:
    uvicorn.run(app, host="0.0.0.0", port=8090)


if __name__ == "__main__":
    main()
