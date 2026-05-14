from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    brier_score_loss,
    classification_report,
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import GroupShuffleSplit, StratifiedShuffleSplit
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data_sources" / "modeling"
DATASET = OUT / "firesky_training_dataset.csv"

LEAKAGE_OR_METADATA = {
    "source",
    "city",
    "date",
    "event_time",
    "label_good",
    "quality_ordinal",
    "quality_source_value",
    "raw_posts",
    "is_primary_train",
    "is_selection_biased",
    "observed_cloud",
    "color",
    "actual_quality",
    "predicted_quality",
    "consistency",
    "sky_condition",
    "case_url",
}


def make_feature_lists(frame: pd.DataFrame) -> tuple[list[str], list[str]]:
    numeric = []
    for column in frame.columns:
        if column in LEAKAGE_OR_METADATA or column == "event":
            continue
        if pd.api.types.is_numeric_dtype(frame[column]):
            missing_rate = frame[column].isna().mean()
            if missing_rate < 0.98:
                numeric.append(column)
    categorical = ["event"]
    return numeric, categorical


def make_logistic(numeric: list[str], categorical: list[str]) -> Pipeline:
    preprocess = ColumnTransformer(
        transformers=[
            (
                "num",
                Pipeline(
                    steps=[
                        ("imputer", SimpleImputer(strategy="median")),
                        ("scaler", StandardScaler()),
                    ]
                ),
                numeric,
            ),
            (
                "cat",
                Pipeline(
                    steps=[
                        ("imputer", SimpleImputer(strategy="most_frequent")),
                        ("onehot", OneHotEncoder(handle_unknown="ignore", sparse_output=False)),
                    ]
                ),
                categorical,
            ),
        ],
        remainder="drop",
        verbose_feature_names_out=False,
    )
    return Pipeline(
        steps=[
            ("preprocess", preprocess),
            (
                "model",
                LogisticRegression(max_iter=5000, class_weight="balanced", solver="lbfgs"),
            ),
        ]
    )


def make_forest(numeric: list[str], categorical: list[str]) -> Pipeline:
    preprocess = ColumnTransformer(
        transformers=[
            ("num", SimpleImputer(strategy="median"), numeric),
            (
                "cat",
                Pipeline(
                    steps=[
                        ("imputer", SimpleImputer(strategy="most_frequent")),
                        ("onehot", OneHotEncoder(handle_unknown="ignore", sparse_output=False)),
                    ]
                ),
                categorical,
            ),
        ],
        remainder="drop",
        verbose_feature_names_out=False,
    )
    return Pipeline(
        steps=[
            ("preprocess", preprocess),
            (
                "model",
                RandomForestClassifier(
                    n_estimators=600,
                    min_samples_leaf=8,
                    max_features="sqrt",
                    class_weight="balanced_subsample",
                    random_state=42,
                    n_jobs=-1,
                ),
            ),
        ]
    )


def metrics(y_true: pd.Series, proba: np.ndarray, threshold: float = 0.5) -> dict[str, Any]:
    pred = (proba >= threshold).astype(int)
    return {
        "roc_auc": float(roc_auc_score(y_true, proba)),
        "average_precision": float(average_precision_score(y_true, proba)),
        "brier": float(brier_score_loss(y_true, proba)),
        "accuracy_at_0_50": float(accuracy_score(y_true, pred)),
        "precision_at_0_50": float(precision_score(y_true, pred, zero_division=0)),
        "recall_at_0_50": float(recall_score(y_true, pred, zero_division=0)),
        "f1_at_0_50": float(f1_score(y_true, pred, zero_division=0)),
        "positive_rate_at_0_50": float(np.mean(pred)),
        "confusion_at_0_50": confusion_matrix(y_true, pred).tolist(),
    }


def best_f1_threshold(y_true: pd.Series, proba: np.ndarray) -> tuple[float, float]:
    best_threshold = 0.5
    best_score = -1.0
    for threshold in np.linspace(0.05, 0.95, 91):
        score = f1_score(y_true, proba >= threshold, zero_division=0)
        if score > best_score:
            best_threshold = float(threshold)
            best_score = float(score)
    return best_threshold, best_score


def evaluate_model(name: str, model: Pipeline, train: pd.DataFrame, test: pd.DataFrame, features: list[str]) -> dict[str, Any]:
    model.fit(train[features], train["label_good"])
    proba = model.predict_proba(test[features])[:, 1]
    threshold, f1 = best_f1_threshold(test["label_good"], proba)
    result = metrics(test["label_good"], proba)
    result["best_f1_threshold"] = threshold
    result["best_f1"] = f1
    result["classification_report_at_best_f1"] = classification_report(
        test["label_good"],
        proba >= threshold,
        output_dict=True,
        zero_division=0,
    )
    result["model"] = name
    return result


def export_logistic_json(model: Pipeline, numeric: list[str], categorical: list[str], path: Path, threshold: float) -> None:
    preprocess: ColumnTransformer = model.named_steps["preprocess"]
    classifier: LogisticRegression = model.named_steps["model"]
    num_pipe: Pipeline = preprocess.named_transformers_["num"]
    cat_pipe: Pipeline = preprocess.named_transformers_["cat"]
    onehot: OneHotEncoder = cat_pipe.named_steps["onehot"]

    payload = {
        "model_type": "standardized_logistic_regression",
        "positive_label": "good_vivid_sunrise_or_sunset",
        "recommended_threshold": threshold,
        "numeric_features": numeric,
        "categorical_features": categorical,
        "numeric_imputer_median": num_pipe.named_steps["imputer"].statistics_.tolist(),
        "numeric_scaler_mean": num_pipe.named_steps["scaler"].mean_.tolist(),
        "numeric_scaler_scale": num_pipe.named_steps["scaler"].scale_.tolist(),
        "categorical_imputer_value": cat_pipe.named_steps["imputer"].statistics_.tolist(),
        "categorical_categories": [values.tolist() for values in onehot.categories_],
        "feature_names_after_preprocessing": preprocess.get_feature_names_out().tolist(),
        "coefficients": classifier.coef_[0].tolist(),
        "intercept": float(classifier.intercept_[0]),
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def feature_importance(model: Pipeline, top_n: int = 40) -> pd.DataFrame:
    preprocess: ColumnTransformer = model.named_steps["preprocess"]
    names = preprocess.get_feature_names_out()
    forest: RandomForestClassifier = model.named_steps["model"]
    return (
        pd.DataFrame({"feature": names, "importance": forest.feature_importances_})
        .sort_values("importance", ascending=False)
        .head(top_n)
    )


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    frame = pd.read_csv(DATASET)
    frame["is_primary_train"] = frame["is_primary_train"].astype(bool)
    primary = frame[frame["is_primary_train"]].copy()
    supplemental = frame[~frame["is_primary_train"]].copy()

    numeric, categorical = make_feature_lists(primary)
    features = numeric + categorical

    splitter = StratifiedShuffleSplit(n_splits=1, test_size=0.25, random_state=42)
    train_idx, test_idx = next(splitter.split(primary, primary["label_good"]))
    train = primary.iloc[train_idx].copy()
    test = primary.iloc[test_idx].copy()

    models = {
        "logistic": make_logistic(numeric, categorical),
        "random_forest": make_forest(numeric, categorical),
    }

    random_split_results = {
        name: evaluate_model(name, model, train, test, features)
        for name, model in models.items()
    }

    group_splitter = GroupShuffleSplit(n_splits=1, test_size=0.25, random_state=7)
    g_train_idx, g_test_idx = next(group_splitter.split(primary, primary["label_good"], groups=primary["city"]))
    group_train = primary.iloc[g_train_idx].copy()
    group_test = primary.iloc[g_test_idx].copy()
    group_results = {
        name: evaluate_model(name, make_logistic(numeric, categorical) if name == "logistic" else make_forest(numeric, categorical), group_train, group_test, features)
        for name in models
    }

    best_name = max(random_split_results, key=lambda key: random_split_results[key]["roc_auc"])
    final_primary = pd.concat([primary, supplemental], ignore_index=True)
    final_model = make_logistic(numeric, categorical) if best_name == "logistic" else make_forest(numeric, categorical)
    weights = np.where(final_primary["is_selection_biased"].astype(bool), 0.25, 1.0)
    final_model.fit(final_primary[features], final_primary["label_good"], model__sample_weight=weights)

    logistic_final = make_logistic(numeric, categorical)
    logistic_final.fit(final_primary[features], final_primary["label_good"], model__sample_weight=weights)

    joblib.dump(final_model, OUT / "firesky_model.joblib")
    joblib.dump(logistic_final, OUT / "firesky_logistic_model.joblib")
    export_logistic_json(
        logistic_final,
        numeric,
        categorical,
        OUT / "firesky_logistic_model.json",
        random_split_results["logistic"]["best_f1_threshold"],
    )

    if best_name == "random_forest":
        feature_importance(final_model).to_csv(OUT / "feature_importance.csv", index=False, encoding="utf-8")

    report = {
        "dataset_rows": int(len(frame)),
        "primary_rows": int(len(primary)),
        "supplemental_rows": int(len(supplemental)),
        "features_used": len(features),
        "numeric_features_used": len(numeric),
        "categorical_features_used": categorical,
        "random_stratified_split": random_split_results,
        "held_out_city_split": group_results,
        "selected_final_model": best_name,
        "notes": [
            "Model excludes known leakage columns: social post count, observed cloud/color labels, existing sunsetbot prediction, and source id.",
            "Shanghai record-only rows are included in final fitting with 0.25 sample weight because they are selection-biased positive/case records.",
            "Primary validation uses Stanford labels plus Shanghai ERA-5 reanalysis labels only.",
        ],
    }
    (OUT / "model_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
