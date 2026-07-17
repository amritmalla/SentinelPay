from riskmodel.datagen.generator import GeneratorConfig, dataset_hash, generate_dataset


def test_datagen_determinism_same_seed_same_hash() -> None:
    cfg = GeneratorConfig(rows=500, seed=99)
    a = generate_dataset(cfg)
    b = generate_dataset(cfg)
    assert dataset_hash(a) == dataset_hash(b)
    assert list(a.columns) == list(b.columns)


def test_datagen_different_seed_different_hash() -> None:
    a = generate_dataset(GeneratorConfig(rows=500, seed=1))
    b = generate_dataset(GeneratorConfig(rows=500, seed=2))
    assert dataset_hash(a) != dataset_hash(b)


def test_archetype_injection_and_imbalance() -> None:
    frame = generate_dataset(
        GeneratorConfig(rows=2_000, seed=7, fraud_rate=0.02, label_flip_rate=0.0)
    )
    fraud = frame[frame["archetype"] != "legit"]
    assert set(fraud["archetype"].unique()) >= {
        "velocity_burst",
        "card_testing",
        "amount_outlier",
        "geo_mismatch",
        "night_owl",
    }
    rate = float(frame["is_fraud"].mean())
    assert 0.01 <= rate <= 0.04
