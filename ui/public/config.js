// Local dev default. In built containers, docker-entrypoint.sh overwrites this
// file at startup from the API_BASE_URL environment variable, so the same
// image can be deployed unchanged across dev/staging/prod (see deployable.md).
window.__ENV__ = {
  API_BASE_URL: "http://localhost:8080/api",
};
