from riskmodel.features import FEATURE_NAMES
from riskmodel.train.pipeline import TrainConfig, train


def test_train_smoke_produces_metadata_and_beats_chance() -> None:
    meta = train(TrainConfig(rows=800, seed=3, smoke=True))
    assert meta["model_version"].startswith("ml-v")
    assert meta["feature_names"] == list(FEATURE_NAMES)
    assert meta["metrics"]["pr_auc"] > 0.05
    assert "at_0.30" in meta["metrics"]
    assert "at_0.70" in meta["metrics"]
