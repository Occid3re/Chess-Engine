import textwrap

from scripts.auto_tuning_loop import SeedTuningOptimizer


def test_load_param_specs_handles_hex_literals(tmp_path):
    project_root = tmp_path / "project"
    project_root.mkdir()
    (project_root / "pom.xml").write_text("<project />", encoding="utf-8")

    param_dir = project_root / "src/main/java/julius/game/chessengine/tuning"
    param_dir.mkdir(parents=True)
    param_dir.joinpath("ParamId.java").write_text(
        textwrap.dedent(
            """
            package julius.game.chessengine.tuning;

            public enum ParamId {
                HEX_PARAM("search.hexparam", 0x1F, 0x10, 0x2A),
                NEG_HEX_PARAM("search.neghex", -0x1A, -0x20, -0x10);
            }
            """
        ).strip()
        + "\n",
        encoding="utf-8",
    )

    tuning_file = project_root / "seed-tunings.yaml"
    tuning_file.write_text("numericParameters:\n", encoding="utf-8")

    optimizer = SeedTuningOptimizer(tuning_file)
    specs = optimizer._param_specs

    hex_spec = specs["search.hexparam"]
    assert hex_spec.default_value == 31.0
    assert hex_spec.min_value == 16.0
    assert hex_spec.max_value == 42.0

    neg_hex_spec = specs["search.neghex"]
    assert neg_hex_spec.default_value == -26.0
    assert neg_hex_spec.min_value == -32.0
    assert neg_hex_spec.max_value == -16.0
