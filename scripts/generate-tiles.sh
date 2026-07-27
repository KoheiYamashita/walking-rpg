#!/usr/bin/env bash
#
# 対象圏のPMTilesを Protomaps の公開ビルドから切り出す。
#
# 実在の座標をリポジトリに残さないため、bbox は
#   1) 引数 --bbox=W,S,E,N
#   2) Git管理外のローカル設定 scripts/local-config
# のいずれかから受け取る（引数が優先）。
#
# 使い方:
#   cp scripts/local-config.example scripts/local-config   # 編集してbboxを書く
#   scripts/generate-tiles.sh
#   scripts/generate-tiles.sh --bbox=W,S,E,N --maxzoom=15 --out=/path/to/area.pmtiles
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONFIG_FILE="${SCRIPT_DIR}/local-config"

DEFAULT_OUT="${REPO_ROOT}/composeApp/src/androidMain/assets/map/area.pmtiles"
PLANET_INDEX="https://maps.protomaps.com/builds"

BBOX=""
MAXZOOM=""
MINZOOM=""
OUT=""
PLANET=""

die() { echo "error: $*" >&2; exit 1; }

# --- ローカル設定（あれば読む。値の上書きは引数が優先） -----------------------
if [[ -f "${CONFIG_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${CONFIG_FILE}"
  BBOX="${BBOX:-}"
  MAXZOOM="${MAXZOOM:-}"
  MINZOOM="${MINZOOM:-}"
  OUT="${OUT:-}"
  PLANET="${PLANET:-}"
fi

# --- 引数 --------------------------------------------------------------------
for arg in "$@"; do
  case "${arg}" in
    --bbox=*)    BBOX="${arg#*=}" ;;
    --maxzoom=*) MAXZOOM="${arg#*=}" ;;
    --minzoom=*) MINZOOM="${arg#*=}" ;;
    --out=*)     OUT="${arg#*=}" ;;
    --planet=*)  PLANET="${arg#*=}" ;;
    -h|--help)   sed -n '2,12p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *)           die "不明な引数: ${arg}" ;;
  esac
done

MAXZOOM="${MAXZOOM:-15}"
MINZOOM="${MINZOOM:-8}"
OUT="${OUT:-${DEFAULT_OUT}}"

command -v pmtiles >/dev/null 2>&1 \
  || die "pmtiles CLI が見つからない。https://github.com/protomaps/go-pmtiles/releases から入れる"

[[ -n "${BBOX}" ]] \
  || die "bbox が未設定。scripts/local-config を作るか --bbox=W,S,E,N を渡す（例は local-config.example）"

[[ "${BBOX}" =~ ^-?[0-9.]+,-?[0-9.]+,-?[0-9.]+,-?[0-9.]+$ ]] \
  || die "bbox の形式が不正: ${BBOX}（W,S,E,N の4つの数値）"

# --- planet ビルドの決定 ------------------------------------------------------
# 未指定なら直近の日次ビルドを使う（過去数日ぶんを新しい順に探す）。
if [[ -z "${PLANET}" ]]; then
  for days_ago in 1 2 3 4 5 6 7; do
    if date -v-1d >/dev/null 2>&1; then
      candidate="$(date -v-${days_ago}d +%Y%m%d)"   # BSD date (macOS)
    else
      candidate="$(date -d "${days_ago} days ago" +%Y%m%d)"  # GNU date
    fi
    url="https://build.protomaps.com/${candidate}.pmtiles"
    if curl -sfI "${url}" >/dev/null 2>&1; then
      PLANET="${url}"
      break
    fi
  done
fi

[[ -n "${PLANET}" ]] \
  || die "日次ビルドが見つからない。${PLANET_INDEX} を見て --planet=<URL> を指定する"

mkdir -p "$(dirname "${OUT}")"

echo "planet : ${PLANET}"
echo "zoom   : ${MINZOOM}-${MAXZOOM}"
echo "out    : ${OUT}"
echo "（bboxはローカル設定なので表示しない）"

# HTTPレンジリクエストで必要なタイルだけ取るので、planet全体（100GB超）の
# ダウンロードは発生しない。
pmtiles extract "${PLANET}" "${OUT}" \
  --bbox="${BBOX}" \
  --minzoom="${MINZOOM}" \
  --maxzoom="${MAXZOOM}" \
  --download-threads=4

echo
echo "生成完了: ${OUT} ($(du -h "${OUT}" | cut -f1))"
echo
echo "初期表示位置を local.properties に設定する（Git管理外）。bboxの中心なら:"
awk -v b="${BBOX}" 'BEGIN {
  split(b, v, ",");
  printf "  map.center.lat=%.6f\n", (v[2] + v[4]) / 2;
  printf "  map.center.lon=%.6f\n", (v[1] + v[3]) / 2;
  printf "  map.zoom=15\n";
}'
echo
echo "データ: © OpenStreetMap contributors (ODbL) / Protomaps"
