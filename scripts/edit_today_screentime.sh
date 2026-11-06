#!/system/bin/sh
# HyperOS 健康使用手机 今日统计编辑器（Vector/LSPosed 模块版）
# 依赖：com.screentime.editor 模块已在 Vector/LSPosed 中启用并作用域包含 com.xiaomi.misettings
PATH="/system/bin:/system/xbin:/vendor/bin:/sbin:/data/adb/ksu/bin:$PATH"
export PATH

APP="com.xiaomi.misettings"
MODULE="com.screentime.editor"
DIR="/data/user/0/$APP/files/screentime"
CURRENT="$DIR/current.txt"
PLAN="$DIR/plan.txt"
PLAN_DIR="$DIR/plans"
BASELINE_DIR="$DIR/baselines"
BATTERY_PLAN="/data/local/tmp/screentime_editor/plan.txt"
BACKUP_BASE="/data/local/tmp/screentime_editor/backups"
SD_BACKUP="/sdcard/Download/ScreenTimeEditor/backups"
EXPECTED_ROM="OS3.0.5.0.VMRCNXM"
EXPECTED_VERSION_NAME="15.03.1211.10-phone"
EXPECTED_VERSION_CODE="251211320"

WORK=""
CUR_DATE=""
CUR_USER=""
CUR_TOTAL_PERIOD=0
CUR_TOTAL_USAGE=0
APP_COUNT=0
TODAY_MS=""
DAY_MS=""
EXPECT_MS=""
T=0
PLAN_FILE=""
DATED_PLAN=""

die() { printf '错误: %s\n' "$*" >&2; exit 1; }
say() { printf '%s\n' "$*"; }
warn() { printf '警告: %s\n' "$*" >&2; }
clear_screen() { [ -t 1 ] && { clear 2>/dev/null || printf '\033[H\033[2J'; }; }
trim() { printf '%s' "$1" | tr -d ' \t\r\n'; }
strip0() { _v="$1"; while [ "${_v#0}" != "$_v" ] && [ ${#_v} -gt 1 ]; do _v="${_v#0}"; done; printf '%s' "$_v"; }
valid_uint() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
fmt_min() {
    _m="$1"
    [ "$_m" -le 0 ] && { printf '不足1分钟'; return; }
    _h=$((_m / 60)); _mm=$((_m % 60))
    if [ "$_h" -gt 0 ] && [ "$_mm" -gt 0 ]; then printf '%d小时%d分钟' "$_h" "$_mm"
    elif [ "$_h" -gt 0 ]; then printf '%d小时' "$_h"
    else printf '%d分钟' "$_mm"; fi
}
pause_enter() { printf '\n按回车继续...'; read -r _x || true; }

cleanup() { [ -n "$WORK" ] && [ -d "$WORK" ] && rm -rf "$WORK"; }
trap cleanup 0; trap 'cleanup; exit 130' 1 2 15

check_root() { [ "$(id -u 2>/dev/null)" = "0" ] || die "需要 Root。请先 su"; }
check_device() {
    DEV=$(getprop ro.product.device); SDK=$(getprop ro.build.version.sdk)
    REL=$(getprop ro.build.version.release); INC=$(getprop ro.build.version.incremental)
    FP=$(getprop ro.build.fingerprint); DID=$(getprop ro.build.display.id)
    [ "$DEV" = "marble" ] || die "设备不匹配：$DEV"
    [ "$SDK" = "35" ] || die "SDK 不匹配：$SDK"
    case "$REL" in 15*) ;; *) die "Android 版本不匹配：$REL" ;; esac
    case "$INC $FP $DID" in *"$EXPECTED_ROM"*) ;; *) die "ROM 不匹配：${INC:-?}" ;; esac
    say "设备：$DEV / Android $REL (SDK $SDK) / ${INC:-$DID}"
}
check_pkg() {
    _d=$(dumpsys package "$APP" 2>/dev/null)
    VN=$(printf '%s\n' "$_d" | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)
    VC=$(printf '%s\n' "$_d" | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1)
    if [ "$VN" != "$EXPECTED_VERSION_NAME" ] || [ "$VC" != "$EXPECTED_VERSION_CODE" ]; then
        die "MISettings 版本不匹配：$VN ($VC)"
    fi
    say "MISettings：$VN ($VC)"
}
check_module() {
    pm path "$MODULE" >/dev/null 2>&1 || die "未安装模块 $MODULE。请先安装模块 APK，并在 Vector 中启用、作用域勾选 com.xiaomi.misettings。"
    say "模块：已安装 $MODULE"
}
ensure_dir() {
    mkdir -p "$DIR" 2>/dev/null || die "无法创建 $DIR"
    chown 1000:1000 "$DIR" 2>/dev/null || true
    chmod 770 "$DIR" 2>/dev/null || true
}
load_current() {
    ensure_dir
    _try=0
    while [ ! -s "$CURRENT" ]; do
        _try=$((_try + 1))
        say ""
        say "还没有 $(basename "$CURRENT")。"
        say "请打开：设置 -> 健康使用手机 -> 对应日期，等统计加载完成，再回到终端按回车。"
        printf '按回车重试（第 %d 次，输入 q 退出）: ' "$_try"
        read -r _a || return 1
        [ "$(trim "$_a")" = "q" ] && return 1
        [ "$_try" -ge 10 ] && { warn "多次未读到 current.txt，请检查模块是否启用/作用域是否包含 MISettings，并查看 Vector 日志。"; return 1; }
    done
    CUR_DATE=$(sed -n 's/^date=//p' "$CURRENT" | head -1)
    CUR_USER=$(sed -n 's/^user=//p' "$CURRENT" | head -1)
    CUR_TOTAL_PERIOD=$(sed -n 's/^total_period=//p' "$CURRENT" | head -1)
    CUR_TOTAL_USAGE=$(sed -n 's/^total_usage=//p' "$CURRENT" | head -1)
    valid_uint "$CUR_DATE" || die "current.txt date 无效"
    valid_uint "$CUR_USER" || die "current.txt user 无效"
    if [ "$CUR_DATE" != "$EXPECT_MS" ]; then
        warn "current 快照日期不匹配（$CUR_DATE != $EXPECT_MS）"
        return 1
    fi
    valid_uint "$CUR_TOTAL_PERIOD" || CUR_TOTAL_PERIOD=0
    valid_uint "$CUR_TOTAL_USAGE" || CUR_TOTAL_USAGE=0
    STATS_FILE="$WORK/stats.txt"
    awk -F'|' 'NF>=2 && $1 !~ /^(date|user|total_usage|total_period)=/ {print $1"|"$2"|"$3}' "$CURRENT" > "$STATS_FILE"
    APP_COUNT=$(wc -l < "$STATS_FILE" | tr -d ' ')
    [ "$APP_COUNT" -gt 0 ] || { warn "current.txt 没有应用明细；请先打开今日页面。"; return 1; }
    return 0
}
show_stats() {
    load_current || return 1
    say ""
    say "统计日期：date=$CUR_DATE user=$CUR_USER"
    if [ -f "$PLAN" ] && [ "$(plan_lock_state)" = "1" ]; then say "状态：已锁定"; else say "状态：未锁定"; fi
    say "页面总时长（periods）：$(fmt_min $((CUR_TOTAL_PERIOD / 60000)))（$((CUR_TOTAL_PERIOD / 60000)) 分钟）"
    say "应用明细 usage 合计：$(fmt_min $((CUR_TOTAL_USAGE / 60000)))（$((CUR_TOTAL_USAGE / 60000)) 分钟）"
    say "应用条目：$APP_COUNT"
    say ""
    printf '%3s  %-46s %10s %12s\n' "序号" "包名" "显示" "usage(ms)"
    _i=1
    while IFS='|' read -r _pkg _ums _pms; do
        [ -n "$_pkg" ] || continue
        printf '%3d  %-46s %10s %12s\n' "$_i" "$_pkg" "$(fmt_min $((_ums / 60000)))" "$_ums"
        _i=$((_i + 1))
    done < "$STATS_FILE"
    if [ "$((CUR_TOTAL_PERIOD / 60000))" != "$((CUR_TOTAL_USAGE / 60000))" ]; then
        say ""
        say "注意：periods 与 usage 的取整结果存在差异，页面本身可能就有该差异。"
    fi
    return 0
}
plan_init() {
    PLAN_FILE="$WORK/plan.tsv"
    awk -F'|' '{printf "%s\t%d\t%d\t%d\n", $1, int($2/60000), int($2/60000), $2}' "$STATS_FILE" > "$PLAN_FILE"
}
plan_sum() { awk -F'	' '{s+=$3} END{printf "%d",s+0}' "$PLAN_FILE"; }
plan_count() { wc -l < "$PLAN_FILE" | tr -d ' '; }
plan_update() {
    awk -F'	' -v OFS='	' -v p="$1" -v v="$2" '$1==p{$3=v}{print}' "$PLAN_FILE" > "$PLAN_FILE.new" && mv -f "$PLAN_FILE.new" "$PLAN_FILE"
}
plan_show() {
    say ""
    printf '%3s  %-46s %8s %8s\n' "序号" "包名" "原分钟" "新分钟"
    _i=1
    while IFS='	' read -r _pkg _o _n _base; do
        [ -n "$_pkg" ] || continue
        printf '%3d  %-46s %8d %8d\n' "$_i" "$_pkg" "$_o" "$_n"
        _i=$((_i + 1))
    done < "$PLAN_FILE"
    _s=$(plan_sum)
    say ""
    say "应用合计：$_s 分钟 / 目标：$T 分钟 / 差额：$((T - _s))"
}
ask_total() {
    _cur=$((CUR_TOTAL_PERIOD / 60000))
    say ""
    say "当前页面总时长：$(fmt_min "$_cur")（$_cur 分钟）"
    while :; do
        printf '目标总时长（分钟，1-1440）: '
        read -r _a || return 1
        _a=$(trim "$_a")
        if valid_uint "$_a" && [ "$_a" -ge 1 ] && [ "$_a" -le 1440 ]; then T=$_a; return 0; fi
        say "请输入 1-1440 的整数分钟。"
    done
}
auto_allocate() {
    awk -F'|' -v T="$T" '
        {n++; pkg[n]=$1; w[n]=int($2/60000); b[n]=$2; sum+=w[n]}
        END {
            if (n==0) exit 1;
            if (sum==0) { base=int(T/n); rem=T-base*n; for(i=1;i<=n;i++) v[i]=base+(i<=rem?1:0); }
            else { rem=T; for(i=1;i<=n;i++){v[i]=int(T*w[i]/sum); rem-=v[i];} mi=1; for(i=2;i<=n;i++) if(w[i]>w[mi]) mi=i; v[mi]+=rem; }
            for(i=1;i<=n;i++) printf "%s\t%d\t%d\t%d\n", pkg[i], w[i], v[i], b[i];
        }' "$STATS_FILE" > "$PLAN_FILE"
    [ -s "$PLAN_FILE" ] || return 1
    [ "$(plan_sum)" = "$T" ] || return 1
}
individual_edit() {
    _total=$(plan_count); _i=1
    while [ "$_i" -le "$_total" ]; do
        _line=$(sed -n "${_i}p" "$PLAN_FILE")
        _pkg=$(printf '%s' "$_line" | cut -f1); _o=$(printf '%s' "$_line" | cut -f2); _c=$(printf '%s' "$_line" | cut -f3)
        [ -n "$_pkg" ] || { _i=$((_i+1)); continue; }
        say ""
        say "[$_i/$_total] $_pkg  原始 $_o 分钟  当前 $_c 分钟"
        printf '目标分钟 [回车=保留, 数字, o=恢复, k=保留其余, q=取消]: '
        read -r _a || return 1; _a=$(trim "$_a")
        case "$_a" in
            "") _i=$((_i+1)); continue;;
            k|K) break;;
            q|Q) return 1;;
            o|O) plan_update "$_pkg" "$_o"; _i=$((_i+1)); continue;;
        esac
        if valid_uint "$_a" && [ "$_a" -ge 0 ] && [ "$_a" -le 1440 ]; then plan_update "$_pkg" "$_a"; _i=$((_i+1)); else say "请输入 0-1440。"; fi
    done
}
edit_one() {
    _total=$(plan_count)
    printf '第几个应用（1-%d）: ' "$_total"; read -r _n || return 1; _n=$(trim "$_n")
    if ! valid_uint "$_n" || [ "$_n" -lt 1 ] || [ "$_n" -gt "$_total" ]; then say "序号无效"; return 1; fi
    _line=$(sed -n "${_n}p" "$PLAN_FILE"); _pkg=$(printf '%s' "$_line" | cut -f1); _o=$(printf '%s' "$_line" | cut -f2)
    printf '新目标分钟（0-1440）: '; read -r _v || return 1; _v=$(trim "$_v")
    if ! valid_uint "$_v" || [ "$_v" -gt 1440 ]; then say "无效"; return 1; fi
    plan_update "$_pkg" "$_v"
}
auto_adjust() {
    awk -F'	' -v OFS='	' -v target="$T" '
        {n++;pkg[n]=$1;orig[n]=$2;val[n]=$3;base[n]=$4;sum+=$3}
        END{
            if(n==0)exit 1;
            if(sum<target){mi=1;for(i=2;i<=n;i++)if(val[i]>val[mi])mi=i;val[mi]+=target-sum;}
            else if(sum>target){rem=sum-target;while(rem>0){mi=0;for(i=1;i<=n;i++)if(val[i]>0&&(mi==0||val[i]>val[mi]))mi=i;if(mi==0)exit 2;take=(val[mi]<rem?val[mi]:rem);val[mi]-=take;rem-=take;}}
            for(i=1;i<=n;i++)printf "%s\t%d\t%d\t%d\n",pkg[i],orig[i],val[i],base[i];
        }' "$PLAN_FILE" > "$PLAN_FILE.new" || { say "无法自动调整"; rm -f "$PLAN_FILE.new"; return 1; }
    mv -f "$PLAN_FILE.new" "$PLAN_FILE"
}
plan_check() {
    while :; do
        _s=$(plan_sum); _d=$((T - _s))
        [ "$_d" -eq 0 ] && return 0
        say ""; say "合计 $_s 分钟，目标 $T 分钟，差额 $_d 分钟，不能提交。"
        say "1) 修改某个应用  2) 自动调整  3) 取消"
        printf '选择: '; read -r _c || return 1; _c=$(trim "$_c")
        case "$_c" in 1) edit_one;; 2) auto_adjust;; 3) return 1;; *) say "无效";; esac
    done
}
backup_plan() {
    _stamp=$(date +%Y%m%d_%H%M%S)
    _b="$BACKUP_BASE/$_stamp"; mkdir -p "$_b"; chmod 700 "$_b" 2>/dev/null
    [ -f "$CURRENT" ] && cp -p "$CURRENT" "$_b/current.txt" 2>/dev/null
    [ -f "$PLAN" ] && cp -p "$PLAN" "$_b/plan.txt" 2>/dev/null
    [ -f "$DIR/lock.txt" ] && cp -p "$DIR/lock.txt" "$_b/lock.txt" 2>/dev/null
    [ -n "$TODAY_MS" ] && [ -f "$PLAN_DIR/$TODAY_MS.txt" ] && cp -p "$PLAN_DIR/$TODAY_MS.txt" "$_b/dated-plan.txt" 2>/dev/null
    cp -f "$WORK/plan.tsv" "$_b/plan.tsv" 2>/dev/null
    { echo "time=$(date '+%F %T %Z')"; echo "date=$CUR_DATE"; echo "user=$CUR_USER"; echo "target=$T"; } > "$_b/manifest.txt"
    if [ -d /sdcard ]; then
        if mkdir -p "$SD_BACKUP/$_stamp" 2>/dev/null; then
            cp -f "$_b"/* "$SD_BACKUP/$_stamp/" 2>/dev/null || true
        fi
    fi
    say "备份：$_b"
}
write_plan() {
    _sum=$(plan_sum); [ "$_sum" = "$T" ] || { warn "合计 $_sum != 目标 $T"; return 1; }
    mkdir -p "$PLAN_DIR" "$BASELINE_DIR" 2>/dev/null || true
    chown 1000:1000 "$PLAN_DIR" "$BASELINE_DIR" 2>/dev/null || true
    chmod 770 "$PLAN_DIR" "$BASELINE_DIR" 2>/dev/null || true

    _is_today=0
    [ "$DAY_MS" = "$TODAY_MS" ] && _is_today=1

    # 只有写今天时才迁移旧的根 plan.txt。
    if [ "$_is_today" = "1" ] && [ -f "$PLAN" ]; then
        _old_date=$(sed -n 's/^date=//p' "$PLAN" | head -1)
        case "$_old_date" in
            ''|*[!0-9]*) ;;
            *)
                if [ "$_old_date" != "$TODAY_MS" ] && [ ! -f "$PLAN_DIR/$_old_date.txt" ]; then
                    cp -p "$PLAN" "$PLAN_DIR/$_old_date.txt" 2>/dev/null || true
                    chown 1000:1000 "$PLAN_DIR/$_old_date.txt" 2>/dev/null || true
                    chmod 660 "$PLAN_DIR/$_old_date.txt" 2>/dev/null || true
                    if [ -f "$DIR/baseline.txt" ]; then
                        cp -p "$DIR/baseline.txt" "$BASELINE_DIR/$_old_date.txt" 2>/dev/null || true
                        chown 1000:1000 "$BASELINE_DIR/$_old_date.txt" 2>/dev/null || true
                        chmod 660 "$BASELINE_DIR/$_old_date.txt" 2>/dev/null || true
                    fi
                    say "已归档旧计划：$PLAN_DIR/$_old_date.txt"
                fi
                ;;
        esac
    fi

    _tmp="$PLAN.tmp.$$"
    {
        echo "date=$CUR_DATE"
        echo "user=$CUR_USER"
        if [ "$_is_today" = "1" ]; then
            echo "mode=delta"
        else
            echo "mode=fixed"
        fi
        echo "base_at=$(date +%s)"
        echo "base_total=$CUR_TOTAL_USAGE"
        echo "total=$T"
        echo "lock=0"
        while IFS="$(printf '\t')" read -r _pkg _o _n _base; do
            [ -n "$_pkg" ] && echo "$_pkg|$_n|${_base:-0}"
        done < "$PLAN_FILE"
    } > "$_tmp" || return 1
    chown 1000:1000 "$_tmp" 2>/dev/null || true
    chmod 660 "$_tmp" 2>/dev/null || true
    mv -f "$_tmp" "$PLAN" || return 1
    chown 1000:1000 "$PLAN" 2>/dev/null || true
    chmod 660 "$PLAN" 2>/dev/null || true

    if [ "$_is_today" = "1" ]; then
        DATED_PLAN="$PLAN_DIR/$TODAY_MS.txt"
        cp -f "$PLAN" "$DATED_PLAN" 2>/dev/null || return 1
        chown 1000:1000 "$DATED_PLAN" 2>/dev/null || true
        chmod 660 "$DATED_PLAN" 2>/dev/null || true

        # 今天的新计划必须在首次加载时重新捕获基线。
        rm -f "$BASELINE_DIR/$TODAY_MS.txt"
        rm -f "$DIR/baseline.txt"
        rm -f "$DIR/effective.txt"
        rm -f "$DIR/lock.txt"
        mkdir -p /data/local/tmp/screentime_editor 2>/dev/null || true
        cp -f "$PLAN" "$BATTERY_PLAN" 2>/dev/null || true
        chmod 644 "$BATTERY_PLAN" 2>/dev/null || true
    else
        DATED_PLAN="$PLAN"
        # 历史日期使用 fixed 模式，不需要 baseline。
        rm -f "$BASELINE_DIR/$DAY_MS.txt"
    fi
    sync
    return 0
}

stop_app() {
    am force-stop "$APP" >/dev/null 2>&1 || true
    sleep 1
    _pids=$(ps -A 2>/dev/null | awk '/[c]om.xiaomi.misettings/ {print $1}')
    for _p in $_pids; do kill -9 "$_p" 2>/dev/null || true; done
    sleep 1
}
start_app() {
    am start -n "$APP/com.xiaomi.misettings.usagestats.UsageStatsMainActivity" >/dev/null 2>&1 || true
    sleep 1
}
restart_settings() {
    say "正在强制重启设置…"
    stop_app
    start_app
    say "已强制重启设置。"
}
apply_plan() {
    backup_plan || return 1
    stop_app
    write_plan || { start_app; return 1; }
    start_app
    if [ "$DAY_MS" = "$TODAY_MS" ]; then
        say "已写入今天的 plan 并重启 MISettings。"
        say "请打开 设置 -> 健康使用手机 -> 今日 核对。"
    else
        say "已写入历史日期 plan：$PLAN"
        say "请打开 设置 -> 健康使用手机，切换到对应日期核对。"
    fi
    return 0
}
restore_plan() {
    if [ -f "$PLAN" ]; then
        cp -p "$PLAN" "$BACKUP_BASE/removed_plan_$(date +%Y%m%d_%H%M%S).txt" 2>/dev/null
        rm -f "$PLAN"
    fi
    rm -f "$PLAN_DIR/$TODAY_MS.txt"
    rm -f "$BASELINE_DIR/$TODAY_MS.txt"
    rm -f "$DIR/baseline.txt"
    rm -f "$DIR/lock.txt"
    rm -f "$BATTERY_PLAN"
    stop_app
    start_app
    say "已删除 plan.txt 并重启 MISettings，页面会回到实时数据。"
}
reset_today_vars() {
    DAY_MS="$TODAY_MS"
    EXPECT_MS="$TODAY_MS"
    CURRENT="$DIR/current.txt"
    PLAN="$DIR/plan.txt"
}

plan_lock_state() {
    [ -f "$PLAN" ] || return 1
    sed -n 's/^lock=//p' "$PLAN" | head -1
}

patch_lock_file() {
    _f="$1"; _v="$2"
    [ -f "$_f" ] || return 0
    _tmp="$WORK/lock.$$"
    awk -v v="$_v" 'BEGIN{done=0} /^lock=/{print "lock="v; done=1; next} {print} END{if(!done) print "lock="v}' "$_f" > "$_tmp" || return 1
    chown 1000:1000 "$_tmp" 2>/dev/null || true
    chmod 660 "$_tmp" 2>/dev/null || true
    mv -f "$_tmp" "$_f" || return 1
    chown 1000:1000 "$_f" 2>/dev/null || true
    chmod 660 "$_f" 2>/dev/null || true
}

toggle_lock() {
    reset_today_vars
    if [ ! -s "$PLAN" ]; then
        say "今天还没有 plan，请先设置今天的目标。"
        pause_enter
        return
    fi
    _old=$(plan_lock_state)
    if [ "$_old" = "1" ]; then _new=0; else _new=1; fi
    patch_lock_file "$PLAN" "$_new" || { say "写入失败"; pause_enter; return; }
    [ -s "$PLAN_DIR/$TODAY_MS.txt" ] && patch_lock_file "$PLAN_DIR/$TODAY_MS.txt" "$_new"
    if [ "$_new" = "1" ]; then
        rm -f "$DIR/lock.txt"
    fi
    stop_app
    start_app
    if [ "$_new" = "1" ]; then
        say "已开启锁定：下次页面加载后冻结当前显示值。"
    else
        say "已解除锁定：下次页面加载时从锁定值继续累计。"
    fi
}

modify_today() {
    reset_today_vars
    show_stats || { pause_enter; return; }
    ask_total || { pause_enter; return; }
    say ""; say "1) 逐个设置  2) 按原比例自动分配"
    printf '选择 [1-2，默认1]: '; read -r _m || return 1; _m=$(trim "$_m")
    plan_init
    case "$_m" in
        2) if auto_allocate && plan_show; then :; else warn "自动分配失败"; pause_enter; return; fi ;;
        *) individual_edit || { say "已取消"; pause_enter; return; } ;;
    esac
    plan_check || { say "已取消"; pause_enter; return; }
    plan_show
    printf '\n确认写入？输入 y: '; read -r _a || return 1
    case "$(trim "$_a")" in y|Y) ;; *) say "已取消"; pause_enter; return;; esac
    apply_plan
    pause_enter
}
modify_history() {
    say ""
    say "1. 昨天"
    say "2. 前天"
    say "3. 输入日期毫秒（date=...）"
    printf '选择 [1-3]: '; read -r _d || return 1; _d=$(trim "$_d")
    case "$_d" in
        1) DAY_MS=$((TODAY_MS - 86400000)) ;;
        2) DAY_MS=$((TODAY_MS - 172800000)) ;;
        3)
            printf '输入该日期的 dayStart 毫秒: '; read -r _d2 || return 1; _d2=$(trim "$_d2")
            valid_uint "$_d2" || { say "日期无效"; pause_enter; return; }
            DAY_MS="$_d2"
            ;;
        *) say "无效"; pause_enter; return ;;
    esac
    if [ "$DAY_MS" -ge "$TODAY_MS" ]; then
        say "历史日期必须早于今天。"
        pause_enter
        return
    fi
    EXPECT_MS="$DAY_MS"
    CURRENT="$DIR/current_$DAY_MS.txt"
    PLAN="$PLAN_DIR/$DAY_MS.txt"
    if [ ! -s "$CURRENT" ]; then
        say "还没有 $CURRENT"
        say "请先在健康使用手机中打开该日期页面，等待加载完成后再回来。"
        pause_enter
        return
    fi
    show_stats || { pause_enter; return; }
    ask_total || { pause_enter; return; }
    say ""; say "1) 逐个设置  2) 按原比例自动分配"
    printf '选择 [1-2，默认1]: '; read -r _m || return 1; _m=$(trim "$_m")
    plan_init
    case "$_m" in
        2) if auto_allocate && plan_show; then :; else warn "自动分配失败"; pause_enter; return; fi ;;
        *) individual_edit || { say "已取消"; pause_enter; return; } ;;
    esac
    plan_check || { say "已取消"; pause_enter; return; }
    plan_show
    printf '\n确认写入历史日期？输入 y: '; read -r _a || return 1
    case "$(trim "$_a")" in y|Y) ;; *) say "已取消"; pause_enter; return;; esac
    apply_plan
    pause_enter
}

view_plan() {
    say ""
    if [ -f "$PLAN" ]; then say "当前 plan.txt："; cat "$PLAN"; else say "当前没有 plan.txt（显示实时数据）。"; fi
    say ""; say "最近日志："
    if [ -f "$BACKUP_BASE/history.log" ]; then tail -20 "$BACKUP_BASE/history.log"; else say "无"; fi
    pause_enter
}
main_menu() {
    while :; do
        clear_screen
        say ""
        say "================================================"
        say "  HyperOS 健康使用手机 - 屏幕使用统计编辑器 (Vector)"
        say "================================================"
        say "设备：$DEV  ROM：${INC:-$DID}"
        say "日期：$(date '+%Y-%m-%d %H:%M:%S')"
        say "模块：$MODULE"
        say "1. 查看今日统计"
        say "2. 修改今日统计"
        say "3. 修改历史日期（需先打开该日期页面）"
        say "4. 恢复今天实时数据（删除今天的 plan）"
        say "5. 查看 plan/日志"
        say "6. 锁定/解除锁定今天的时长"
        say "7. 强制重启设置"
        say "8. 退出"
        printf '请选择 [1-8]: '; read -r _c || exit 0; _c=$(trim "$_c")
        case "$_c" in
            1) reset_today_vars; show_stats && pause_enter;;
            2) modify_today;;
            3) modify_history;;
            4) reset_today_vars; restore_plan; pause_enter;;
            5) reset_today_vars; view_plan;;
            6) toggle_lock; pause_enter;;
            7) restart_settings; pause_enter;;
            8) exit 0;;
            *) say "无效";;
        esac
    done
}
main() {
    check_root
    WORK=$(mktemp -d /data/local/tmp/screentime.XXXXXX 2>/dev/null) || WORK="/data/local/tmp/screentime.$$"
    mkdir -p "$WORK" || die "无法创建临时目录"
    chmod 700 "$WORK" 2>/dev/null
    mkdir -p "$BACKUP_BASE" 2>/dev/null; chmod 700 "$BACKUP_BASE" 2>/dev/null
    check_device
    check_pkg
    check_module
    ensure_dir
    _now=$(date +%s); _hms=$(date +%H:%M:%S)
    _h=${_hms%%:*}; _r=${_hms#*:}; _m=${_r%%:*}; _s=${_r#*:}
    _h=$(strip0 "$_h"); _m=$(strip0 "$_m"); _s=$(strip0 "$_s")
    TODAY_MS=$(awk -v n="$_now" -v h="$_h" -v m="$_m" -v s="$_s" 'BEGIN{printf "%.0f",(n-h*3600-m*60-s)*1000}')
    DAY_MS="$TODAY_MS"
    EXPECT_MS="$TODAY_MS"
    say "今日日界线：$TODAY_MS"
    main_menu
}
main "$@"
