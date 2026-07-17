from riskmodel.features import FEATURE_NAMES, TransactionFeatures, build_features
from riskmodel.features.builder import feature_vector


def test_feature_names_stable_and_ordered() -> None:
    assert "log_amount_cents" in FEATURE_NAMES
    assert "velocity_last_hour" in FEATURE_NAMES
    assert "cat_unknown" in FEATURE_NAMES
    assert FEATURE_NAMES == tuple(build_features(TransactionFeatures(1, "a@b.com", 0)).keys())


def test_empty_category_and_countries_treated_as_missing() -> None:
    feats = build_features(
        TransactionFeatures(
            amount_cents=1000,
            customer_email="buyer@example.com",
            velocity_last_hour=0,
            merchant_category="",
            card_country="",
            merchant_country="",
            timestamp_epoch_ms=None,
        )
    )
    assert feats["cat_unknown"] == 1.0
    assert feats["geo_known"] == 0.0
    assert feats["geo_mismatch"] == 0.0
    assert feats["hour_known"] == 0.0


def test_geo_mismatch_when_both_present() -> None:
    feats = build_features(
        TransactionFeatures(
            amount_cents=1000,
            customer_email="buyer@example.com",
            velocity_last_hour=0,
            merchant_category="retail",
            card_country="GB",
            merchant_country="US",
            timestamp_epoch_ms=1_700_000_000_000,
        )
    )
    assert feats["geo_mismatch"] == 1.0
    assert feats["geo_known"] == 1.0
    assert feats["cat_retail"] == 1.0


def test_disposable_email_and_vector_length() -> None:
    feats = build_features(
        TransactionFeatures(
            amount_cents=100,
            customer_email="x@mailinator.com",
            velocity_last_hour=4,
        )
    )
    assert feats["disposable_email"] == 1.0
    vec = feature_vector(TransactionFeatures(100, "x@mailinator.com", 4))
    assert vec.shape == (len(FEATURE_NAMES),)


def test_unknown_category_bucket() -> None:
    feats = build_features(TransactionFeatures(1000, "a@b.com", 0, merchant_category="aerospace"))
    assert feats["cat_unknown"] == 1.0
    assert feats["cat_retail"] == 0.0
