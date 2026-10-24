import pytest

from app.db_url import check_database_url

BASE = "postgresql+psycopg://u:s3cretpw@db.example.com:5432/app"


def test_prod_remote_without_sslmode_fails():
    with pytest.raises(ValueError, match="sslmode"):
        check_database_url(BASE, "prod")


@pytest.mark.parametrize("mode", ["require", "verify-ca", "verify-full"])
def test_prod_remote_with_tls_ok(mode):
    check_database_url(f"{BASE}?sslmode={mode}", "prod")


@pytest.mark.parametrize("mode", ["disable", "allow", "prefer"])
def test_prod_weak_sslmode_fails(mode):
    with pytest.raises(ValueError):
        check_database_url(f"{BASE}?sslmode={mode}", "prod")


def test_dev_test_and_localhost_ok():
    check_database_url(BASE, "dev")
    check_database_url(BASE, "test")
    check_database_url("postgresql+psycopg://u:p@localhost:5440/x", "prod")
    check_database_url("postgresql://u:p@127.0.0.1/x", "prod")


@pytest.mark.parametrize("url", [None, "", "mysql://u:s3cretpw@h/x", "not a url s3cretpw"])
def test_bad_urls_fail(url):
    with pytest.raises(ValueError):
        check_database_url(url, "dev")


def test_message_never_contains_password():
    for url, env in [(BASE, "prod"), ("mysql://u:s3cretpw@h/x", "dev"), ("http://u:s3cretpw@h:x/y", "dev")]:
        with pytest.raises(ValueError) as e:
            check_database_url(url, env)
        assert "s3cretpw" not in str(e.value)
