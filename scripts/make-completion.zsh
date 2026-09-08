#
# make-completion.zsh — make 자동완성을 Makefile 의 `## ` 주석 타깃으로 제한.
#
# 활성화: ~/.zshrc 에 아래 한 줄 추가 후 새 셸 실행 (경로는 실제 위치로)
#   source "<저장소 경로>/scripts/make-completion.zsh"
#
# 동작:
#   - 현재 디렉터리의 Makefile 에서 `target: ... ## 설명` 형식의 타깃만 완성합니다.
#     (파일·디렉터리·make 변수는 노출되지 않음)
#   - 해당 형식의 타깃이 없거나 Makefile 이 없으면 기본 make 완성으로 폴백합니다.
#     → `## ` 컨벤션을 쓰지 않는 다른 프로젝트에는 영향이 없습니다.
#

_resilientpos_make() {
  local mf
  for mf in Makefile makefile GNUmakefile; do
    [[ -f $mf ]] && break
  done

  if [[ ! -f $mf ]]; then
    _make "$@"
    return
  fi

  local -a targets
  targets=(${(f)"$(grep -E '^[a-zA-Z][a-zA-Z0-9_-]*:.*## ' $mf 2>/dev/null \
    | sed -E 's/:[^#]*## /:/' \
    | sed -E 's/[[:space:]]+$//')"})

  if (( ${#targets} )); then
    _describe -t targets 'make 명령' targets
  else
    _make "$@"
  fi
}

compdef _resilientpos_make make gmake
