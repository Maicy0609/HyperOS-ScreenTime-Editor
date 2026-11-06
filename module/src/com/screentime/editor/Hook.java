package com.screentime.editor;

import android.graphics.drawable.Drawable;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "ScreenTimeEditor";
    private static final String DIR = "/data/user/0/com.xiaomi.misettings/files/screentime";
    private static final long DAY_MS = 86400000L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if ("com.miui.securitycenter".equals(lpp.packageName)) {
            installBatteryHook(lpp.classLoader);
            return;
        }
        if (!"com.xiaomi.misettings".equals(lpp.packageName)) return;
        try {
            final ClassLoader cl = lpp.classLoader;
            Class<?> u = Class.forName("y9.u", false, cl);
            XposedBridge.hookAllMethods(u, "e", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length != 1) return;
                        Object arg = param.args[0];
                        if (arg == null || !arg.getClass().getName().equals("com.xiaomi.misettings.base.model.page.DetailPageModel")) return;
                        Object tab = param.thisObject.getClass().getField("h").get(param.thisObject);
                        String tabClass = tab == null ? "null" : tab.getClass().getName();
                        int range = ((Number) call(param.thisObject, "h")).intValue();
                        XposedBridge.log(TAG + " e tab=" + tabClass + " range=" + range + " today=" + todayStart());
                        if (!"x9.r".equals(tabClass)) return;   // 只处理“日”标签
                        long day = todayStart() + ((long) range) * DAY_MS;
                        Object details = detailsOf(arg);
                        if (details == null) return;
                        writeCurrent(details, cl, day);

                        Plan plan = findPlan(day);
                        if (plan == null) return;

                        Object replaced = buildScreenTimeDetails(details, plan, cl, day);
                        if (replaced == null) return;
                        Object business = call(arg, "getBusiness");
                        Object visual = call(arg, "getVisualHealthDetail");
                        Class<?> dpm = Class.forName("com.xiaomi.misettings.base.model.page.DetailPageModel", false, cl);
                        Constructor<?> ctor = dpm.getConstructor(
                                Class.forName("com.xiaomi.misettings.base.model.page.VisualHealthDetails", false, cl),
                                Class.forName("com.xiaomi.misettings.base.model.page.ScreenTimeDetails", false, cl),
                                Class.forName("x9.c", false, cl));
                        param.args[0] = ctor.newInstance(visual, replaced, business);
                        XposedBridge.log(TAG + " replaced day=" + day + " apps=" + plan.apps.size() + " mode=" + plan.mode);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " hook error: " + t);
                    }
                }
            });

            // 健康使用手机首页：HomePageModel(DeviceUsageDetails, NameAndCategoryDetails, ...)
            Class<?> homeModel = Class.forName("com.xiaomi.misettings.features.data.model.HomePageModel", false, cl);
            XposedBridge.hookAllConstructors(homeModel, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length != 4) return;
                        Object dev = param.args[0];
                        Object nac = param.args[1];
                        if (dev == null || nac == null) return;
                        if (!dev.getClass().getName().endsWith("ScreenTimeDetails$DeviceUsageDetails")) return;
                        if (!nac.getClass().getName().endsWith("ScreenTimeDetails$NameAndCategoryDetails")) return;

                        long day = todayStart();
                        Plan plan = findPlan(day);
                        if (plan == null) return;

                        Object newNac = buildAppUsage(nac, plan, cl, day);
                        if (newNac == null) return;
                        long total = appUsageSum(newNac);
                        writeEffective(plan, total);
                        Object newDev = buildDevice(dev, plan, cl, true, total);
                        if (newDev == null) return;
                        param.args[0] = newDev;
                        param.args[1] = newNac;
                        XposedBridge.log(TAG + " home replaced day=" + day + " apps=" + plan.apps.size());
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " home hook error: " + t);
                    }
                }
            });

            // 只保留“日”统计：隐藏日/周/月切换控件，并强制 ViewModel 使用日标签。
            try {
                Class<?> absDetail = Class.forName("com.xiaomi.misettings.base.ui.AbsDetailPage", false, cl);
                XposedBridge.hookAllMethods(absDetail, "z", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object vm = call(param.thisObject, "q");
                            if (vm != null) {
                                Class<?> day = Class.forName("x9.r", false, cl);
                                Object dayTab = day.getField("a").get(null);
                                vm.getClass().getField("h").set(vm, dayTab);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " force day error: " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object tabs = param.thisObject.getClass().getField("h").get(param.thisObject);
                            if (tabs instanceof android.view.View) {
                                ((android.view.View) tabs).setVisibility(android.view.View.GONE);
                                XposedBridge.log(TAG + " day-only tabs hidden");
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " hide tabs error: " + t);
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + " day-only install error: " + t);
            }

            XposedBridge.log(TAG + " hook installed for " + lpp.packageName + "/" + lpp.processName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " install error: " + t);
        }
    }

    /**
     * 省电与电池首页：com.miui.powercenter.batteryhistory.N.v(long) 生成“使用时长”文本。
     * 用 plan.txt 的目标分钟替换参数，保留原生 Spannable 样式。
     */
    private static void installBatteryHook(ClassLoader cl) {
        try {
            Class<?> n = Class.forName("com.miui.powercenter.batteryhistory.N", false, cl);
            XposedBridge.hookAllMethods(n, "v", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length != 1 || !(param.args[0] instanceof Long)) return;
                        Plan plan = findPlan(todayStart());
                        if (plan == null) return;
                        long effectiveMs = readEffective(plan);
                        long targetUs = (effectiveMs > 0 ? effectiveMs : plan.total * 60000L) * 1000L;
                        param.args[0] = targetUs;
                        XposedBridge.log(TAG + " battery target minutes=" + (targetUs / 60000000L));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " battery hook error: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + " battery hook installed for com.miui.securitycenter");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " battery install error: " + t);
        }
    }

    private static Plan findPlan(long day) {
        File dated = new File(DIR + "/plans", day + ".txt");
        if (dated.exists() && dated.canRead()) {
            Plan p = readPlan(dated);
            if (p != null && p.date == day) return p;
        }
        File legacy = new File(DIR, "plan.txt");
        if (legacy.exists() && legacy.canRead()) {
            Plan p = readPlan(legacy);
            if (p != null && p.date == day) return p;
        }
        return null;
    }

    private static Object detailsOf(Object model) {
        try {
            return call(model, "getScreenTimeDetails");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " detailsOf error: " + t);
            return null;
        }
    }

    private static final class Plan {
        long date;
        long total;
        long baseAt;
        long mtime;
        String mode = "fixed";                 // fixed = 固定值；delta = 目标 + 实际增量
        boolean locked;
        Map<String, Long> apps = new TreeMap<>();
        Map<String, Long> baseApps = new TreeMap<>();
    }

    private static Plan readPlan(File f) {
        try {
            Plan p = new Plan();
            p.date = -1;
            p.total = -1;
            p.mtime = f.lastModified();
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.length() == 0 || line.startsWith("#")) continue;
                    if (line.startsWith("date=")) { p.date = Long.parseLong(line.substring(5).trim()); continue; }
                    if (line.startsWith("user=")) continue;
                    if (line.startsWith("total=")) { p.total = Long.parseLong(line.substring(6).trim()); continue; }
                    if (line.startsWith("mode=")) {
                        String m = line.substring(5).trim();
                        if ("delta".equals(m) || "fixed".equals(m)) p.mode = m;
                        continue;
                    }
                    if (line.startsWith("base_at=")) { p.baseAt = Long.parseLong(line.substring(8).trim()); continue; }
                    if (line.startsWith("base_total=")) continue;
                    if (line.startsWith("lock=")) { p.locked = "1".equals(line.substring(5).trim()); continue; }
                    int i = line.indexOf('|');
                    if (i <= 0) continue;
                    String pkg = line.substring(0, i).trim();
                    String[] parts = line.split("\\|", -1);
                    if (parts.length < 2) continue;
                    long min = Long.parseLong(parts[1].trim());
                    if (min < 0) min = 0;
                    p.apps.put(pkg, min);
                    if (parts.length >= 3 && parts[2].trim().length() > 0) {
                        long base = Long.parseLong(parts[2].trim());
                        if (base >= 0) p.baseApps.put(pkg, base);
                    }
                }
            } finally {
                r.close();
            }
            long sum = 0;
            for (Long v : p.apps.values()) sum += v;
            if (p.date < 0 || p.total < 0 || sum != p.total) {
                XposedBridge.log(TAG + " invalid plan sum=" + sum + " total=" + p.total);
                return null;
            }
            return p;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " readPlan error: " + t);
            return null;
        }
    }

    /**
     * 明细页：应用列表 = 目标分钟 + 基线后的实际增量（delta 模式）；
     * 24 小时图按原分布缩放到应用列表合计。
     */
    private static Object buildScreenTimeDetails(Object src, Plan plan, ClassLoader cl, long day) {
        try {
            Object dev = call(src, "getDeviceUsage");
            Object nac = call(src, "getNameAndCategoryDetails");
            Object unlock = call(src, "getUnlockUsage");
            if (dev == null || nac == null) return null;

            Object newNac = buildAppUsage(nac, plan, cl, day);
            if (newNac == null) return null;
            long total = appUsageSum(newNac);
            if (plan.date == todayStart()) writeEffective(plan, total);
            Object newDev = buildDevice(dev, plan, cl, false, total);
            if (newDev == null) return null;

            Class<?> devDetailsCls = Class.forName("com.xiaomi.misettings.base.model.page.ScreenTimeDetails$DeviceUsageDetails", false, cl);
            Class<?> nacDetailsCls = Class.forName("com.xiaomi.misettings.base.model.page.ScreenTimeDetails$NameAndCategoryDetails", false, cl);
            Class<?> stdCls = Class.forName("com.xiaomi.misettings.base.model.page.ScreenTimeDetails", false, cl);
            Class<?> unlockCls = Class.forName("com.xiaomi.misettings.base.model.page.ScreenTimeDetails$UnlockUsageDetails", false, cl);
            return stdCls.getConstructor(devDetailsCls, nacDetailsCls, unlockCls)
                    .newInstance(newDev, newNac, unlock);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " buildScreenTimeDetails error: " + t);
            return null;
        }
    }

    /**
     * week=true 用于首页 7 天柱状图：只把最后一根（今天）改成 total，保留历史柱。
     * week=false 用于单日明细：按原 24 小时分布等比例缩放到 total。
     */
    private static Object buildDevice(Object dev, Plan plan, ClassLoader cl, boolean week, long total) {
        try {
            long target = total > 0 ? total : plan.total * 60000L;
            List<?> oldList = (List<?>) call(dev, "getDetail");
            ArrayList<Long> detail = new ArrayList<>();
            if (oldList != null) {
                for (Object v : oldList) detail.add(Math.max(0L, ((Number) v).longValue()));
            }

            long max;
            if (week) {
                if (detail.isEmpty()) detail.add(target);
                else detail.set(detail.size() - 1, target);
                max = 1L;
                for (Long v : detail) max = Math.max(max, v);
            } else {
                long oldSum = 0;
                for (Long v : detail) oldSum += v;
                if (oldSum > 0 && detail.size() > 0) {
                    ArrayList<Long> scaled = new ArrayList<>();
                    long sum = 0;
                    for (Long v : detail) {
                        long nv = Math.round(v.doubleValue() * target / oldSum);
                        if (nv < 0) nv = 0;
                        scaled.add(nv);
                        sum += nv;
                    }
                    long diff = target - sum;
                    if (diff != 0 && !scaled.isEmpty()) {
                        int idx = 0;
                        for (int i = 1; i < scaled.size(); i++) {
                            if (scaled.get(i) > scaled.get(idx)) idx = i;
                        }
                        long nv = scaled.get(idx) + diff;
                        if (nv < 0) nv = 0;
                        scaled.set(idx, nv);
                    }
                    detail = scaled;
                } else {
                    detail.clear();
                    int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                    for (int i = 0; i < 24; i++) detail.add(i == hour ? target : 0L);
                }
                max = 1L;
                for (Long v : detail) max = Math.max(max, v);
            }

            Class<?> devDetailsCls = Class.forName("com.xiaomi.misettings.base.model.page.ScreenTimeDetails$DeviceUsageDetails", false, cl);
            Constructor<?> ctor = devDetailsCls.getConstructor(long.class, List.class, long.class, long.class, long.class);
            return ctor.newInstance(target, detail, max, target, lastCycleFor(plan, dev));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " buildDevice error: " + t);
            return null;
        }
    }

    /** 健康使用手机页面当前实际显示的总时长（含 delta 增量），供电池页读取。 */
    private static void writeEffective(Plan plan, long totalMs) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("date=").append(todayStart()).append('\n');
            sb.append("plan_mtime=").append(plan.mtime).append('\n');
            sb.append("total=").append(totalMs).append('\n');
            writeFile(new File(DIR, "effective.txt"), sb.toString());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " writeEffective error: " + t);
        }
    }

    /** 读取页面显示总时长；计划被重写或日期不匹配时返回 0，由调用方回退到 plan.total。 */
    private static long readEffective(Plan plan) {
        File f = new File(DIR, "effective.txt");
        if (!f.exists()) return 0L;
        try {
            long date = -1;
            long mtime = -1;
            long total = 0;
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("date=")) date = Long.parseLong(line.substring(5).trim());
                    else if (line.startsWith("plan_mtime=")) mtime = Long.parseLong(line.substring(11).trim());
                    else if (line.startsWith("total=")) total = Long.parseLong(line.substring(6).trim());
                }
            } finally {
                r.close();
            }
            if (date == todayStart() && mtime == plan.mtime && total > 0) return total;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " readEffective error: " + t);
        }
        return 0L;
    }

    /**
     * 今天页面的“较昨日”使用 DeviceUsageDetails.lastCycle。
     * 如果昨天有 fixed 计划，则用昨天的目标值，避免昨天已修改但今天仍拿真实值比较。
     */
    private static long lastCycleFor(Plan plan, Object dev) {
        try {
            long orig = ((Number) call(dev, "getLastCycle")).longValue();
            if (plan != null && plan.date == todayStart()) {
                Plan prev = findPlan(todayStart() - DAY_MS);
                if (prev != null && "fixed".equals(prev.mode)) {
                    return prev.total * 60000L;
                }
            }
            return orig;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " lastCycle error: " + t);
            return 0L;
        }
    }

    /** 用 plan 的目标分钟重建应用列表，保留原图标/分类/限制标记。 */
    private static Object buildAppUsage(Object nac, Plan plan, ClassLoader cl, long day) {
        try {
            Object appDetails = call(nac, "getAppDetails");
            if (!(appDetails instanceof List)) return null;
            Map<String, Object> byPkg = new LinkedHashMap<>();
            for (Object item : (List<?>) appDetails) {
                Object pkg = call(item, "getPackageName");
                if (pkg != null) byPkg.put(String.valueOf(pkg), item);
            }

            // delta 模式的基线必须在“计划生效后的第一次页面加载”捕获，
            // 这样刚应用计划时页面严格等于目标值，之后才按真实增量增长。
            Map<String, Long> bases = null;
            Map<String, Long> lock = plan.date == todayStart() ? readLock(plan) : null;
            boolean lockCapture = false;
            if (plan.locked) {
                if (lock == null) lockCapture = true;
            } else if (lock != null) {
                // 解锁：把锁定时的显示值换算成新基线，解锁瞬间不跳变。
                Map<String, Long> newBases = new TreeMap<>();
                for (Map.Entry<String, Long> e : plan.apps.entrySet()) {
                    Object old = byPkg.get(e.getKey());
                    if (old == null) continue;
                    Object oldApp = call(old, "getDetail");
                    if (oldApp == null) continue;
                    long real = ((Number) call(oldApp, "getUsage")).longValue();
                    long targetMs = e.getValue() * 60000L;
                    Long lockedMs = lock.get(e.getKey());
                    long displayMs = lockedMs != null ? lockedMs : targetMs;
                    long base = real - (displayMs - targetMs);
                    if (base < 0) base = 0;
                    newBases.put(e.getKey(), base);
                }
                writeBaseline(plan, newBases, day);
                deleteLock();
                lock = null;
                XposedBridge.log(TAG + " unlock rebaselined apps=" + newBases.size());
            }
            boolean baselineChanged = false;
            boolean justInitialized = false;
            if ("delta".equals(plan.mode) && lock == null) {
                bases = readBaseline(plan, day);
                if (bases == null) {
                    boolean history = day < todayStart();
                    if (history && !plan.baseApps.isEmpty()) {
                        bases = new TreeMap<>(plan.baseApps);
                        XposedBridge.log(TAG + " baseline from plan date=" + day + " apps=" + bases.size());
                    } else {
                        bases = new TreeMap<>();
                        for (String pkg : plan.apps.keySet()) {
                            Object old = byPkg.get(pkg);
                            if (old == null) continue;
                            Object oldApp = call(old, "getDetail");
                            if (oldApp != null) {
                                bases.put(pkg, ((Number) call(oldApp, "getUsage")).longValue());
                            }
                        }
                        writeBaseline(plan, bases, day);
                        justInitialized = true;
                        XposedBridge.log(TAG + " baseline initialized day=" + day + " apps=" + bases.size());
                    }
                }
            }

            Class<?> appItemCls = Class.forName("com.xiaomi.misettings.base.model.item.AppItem", false, cl);
            Class<?> itemCls = Class.forName("com.xiaomi.misettings.base.model.item.NameAndCategoryItem", false, cl);
            Class<?> dataTypeCls = Class.forName("x9.l", false, cl);
            Class<?> appTypeCls = Class.forName("x9.b", false, cl);
            Class<?> groupCls = Class.forName("x9.q", false, cl);

            Constructor<?> appItemCtor = appItemCls.getConstructor(String.class, long.class, String.class,
                    Drawable.class, boolean.class, boolean.class, dataTypeCls, boolean.class, String.class);
            Constructor<?> itemCtor = itemCls.getConstructor(appItemCls, String.class, String.class, String.class,
                    appTypeCls, groupCls, boolean.class);

            List<Object> target = new ArrayList<>();
            Map<String, Long> displayed = new TreeMap<>();
            long sum = 0;
            for (Map.Entry<String, Long> e : plan.apps.entrySet()) {
                Object old = byPkg.get(e.getKey());
                if (old == null) {
                    XposedBridge.log(TAG + " missing package in current list: " + e.getKey());
                    return null;
                }
                Object oldApp = call(old, "getDetail");
                long ms = e.getValue() * 60000L;
                if (lock != null) {
                    Long lockedMs = lock.get(e.getKey());
                    ms = lockedMs != null ? lockedMs : ms;
                } else if ("delta".equals(plan.mode) && bases != null) {
                    long real = ((Number) call(oldApp, "getUsage")).longValue();
                    Long base = bases.get(e.getKey());
                    if (base == null) {
                        base = real;  // 新出现的应用从当前刻开始累计
                        bases.put(e.getKey(), real);
                        baselineChanged = true;
                    }
                    if (!justInitialized) {
                        long extra = real - base;
                        if (extra > 0) ms += (extra / 60000L) * 60000L;
                    }
                }
                Object app = appItemCtor.newInstance(
                        call(oldApp, "getTitle"), ms, call(oldApp, "getIcon"), call(oldApp, "getDrawable"),
                        call(oldApp, "isLimit"), call(oldApp, "isSystem"), call(oldApp, "getDataType"),
                        call(oldApp, "getAsTitle"), call(oldApp, "getKeywords"));
                target.add(itemCtor.newInstance(app, call(old, "getPackageName"),
                        call(old, "getCategoryType"), call(old, "getCategoryId"),
                        call(old, "getAppType"), call(old, "getGroup"), call(old, "getPressEffect")));
                displayed.put(e.getKey(), ms);
                sum += ms;
            }
            if (baselineChanged) writeBaseline(plan, bases, day);
            if (plan.locked && lockCapture) writeLock(plan, displayed);
            if (sum <= 0) {
                XposedBridge.log(TAG + " built empty app list");
                return null;
            }
            XposedBridge.log(TAG + " app total=" + sum + " mode=" + plan.mode);

            Class<?> nacDetailsCls = Class.forName("com.xiaomi.misettings.base.model.page.ScreenTimeDetails$NameAndCategoryDetails", false, cl);
            return nacDetailsCls.getConstructor(List.class, List.class).newInstance(target, null);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " buildAppUsage error: " + t);
            return null;
        }
    }

    private static Map<String, Long> readLock(Plan plan) {
        File f = new File(DIR, "lock.txt");
        if (!f.exists()) return null;
        try {
            long date = -1;
            long baseAt = -1;
            long total = -1;
            Map<String, Long> map = new TreeMap<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.length() == 0 || line.startsWith("#")) continue;
                    if (line.startsWith("date=")) { date = Long.parseLong(line.substring(5).trim()); continue; }
                    if (line.startsWith("base_at=")) { baseAt = Long.parseLong(line.substring(8).trim()); continue; }
                    if (line.startsWith("plan_total=")) { total = Long.parseLong(line.substring(11).trim()); continue; }
                    int i = line.indexOf('|');
                    if (i <= 0) continue;
                    map.put(line.substring(0, i).trim(), Long.parseLong(line.substring(i + 1).trim()));
                }
            } finally {
                r.close();
            }
            if (date == todayStart() && baseAt == plan.baseAt && total == plan.total) return map;
            XposedBridge.log(TAG + " lock stale date=" + date + " base=" + baseAt + "/" + plan.baseAt);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " readLock error: " + t);
        }
        return null;
    }

    private static void writeLock(Plan plan, Map<String, Long> displayed) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("date=").append(todayStart()).append('\n');
            sb.append("base_at=").append(plan.baseAt).append('\n');
            sb.append("plan_total=").append(plan.total).append('\n');
            for (Map.Entry<String, Long> e : displayed.entrySet()) {
                sb.append(e.getKey()).append('|').append(e.getValue()).append('\n');
            }
            writeFile(new File(DIR, "lock.txt"), sb.toString());
            XposedBridge.log(TAG + " lock captured apps=" + displayed.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " writeLock error: " + t);
        }
    }

    private static void deleteLock() {
        File f = new File(DIR, "lock.txt");
        if (f.exists() && !f.delete()) XposedBridge.log(TAG + " cannot delete lock.txt");
    }

    private static Map<String, Long> readBaseline(Plan plan, long day) {
        File f = new File(DIR + "/baselines", day + ".txt");
        if (!f.exists()) return null;
        try {
            long fileDate = -1;
            long fileMtime = -1;
            Map<String, Long> map = new TreeMap<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.length() == 0 || line.startsWith("#")) continue;
                    if (line.startsWith("date=")) { fileDate = Long.parseLong(line.substring(5).trim()); continue; }
                    if (line.startsWith("plan_mtime=")) { fileMtime = Long.parseLong(line.substring(11).trim()); continue; }
                    if (line.startsWith("base_at=")) continue;
                    int i = line.indexOf('|');
                    if (i <= 0) continue;
                    map.put(line.substring(0, i).trim(), Long.parseLong(line.substring(i + 1).trim()));
                }
            } finally {
                r.close();
            }
            if (fileDate != plan.date || fileMtime != plan.mtime) {
                XposedBridge.log(TAG + " baseline stale date=" + fileDate + "/" + plan.date
                        + " mtime=" + fileMtime + "/" + plan.mtime);
                return null;
            }
            return map;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " readBaseline error: " + t);
            return null;
        }
    }

    private static void writeBaseline(Plan plan, Map<String, Long> bases, long day) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("date=").append(plan.date).append('\n');
            sb.append("plan_mtime=").append(plan.mtime).append('\n');
            sb.append("base_at=").append(System.currentTimeMillis()).append('\n');
            for (Map.Entry<String, Long> e : bases.entrySet()) {
                sb.append(e.getKey()).append('|').append(e.getValue()).append('\n');
            }
            writeFile(new File(DIR + "/baselines", day + ".txt"), sb.toString());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " writeBaseline error: " + t);
        }
    }

    private static long appUsageSum(Object nac) throws Exception {
        long sum = 0;
        Object apps = call(nac, "getAppDetails");
        if (apps instanceof List) {
            for (Object item : (List<?>) apps) {
                Object app = call(item, "getDetail");
                if (app != null) sum += Math.max(0L, ((Number) call(app, "getUsage")).longValue());
            }
        }
        return sum;
    }

    private static void writeCurrent(Object details, ClassLoader cl, long day) {
        try {
            Object dev = call(details, "getDeviceUsage");
            Object nac = call(details, "getNameAndCategoryDetails");
            long totalPeriod = dev == null ? 0 : ((Number) call(dev, "getTotalDuration")).longValue();
            Map<String, Long> map = new TreeMap<>();
            long totalUsage = 0;
            if (nac != null) {
                Object apps = call(nac, "getAppDetails");
                if (apps instanceof List) {
                    for (Object item : (List<?>) apps) {
                        Object pkg = call(item, "getPackageName");
                        Object app = call(item, "getDetail");
                        if (pkg == null || app == null) continue;
                        long u = ((Number) call(app, "getUsage")).longValue();
                        String p = String.valueOf(pkg);
                        Long old = map.get(p);
                        map.put(p, (old == null ? 0L : old) + u);
                        totalUsage += u;
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("date=").append(day).append('\n');
            sb.append("user=0\n");
            sb.append("total_usage=").append(totalUsage).append('\n');
            sb.append("total_period=").append(totalPeriod).append('\n');
            for (Map.Entry<String, Long> e : map.entrySet()) {
                sb.append(e.getKey()).append('|').append(e.getValue()).append("|0\n");
            }
            String name = day == todayStart() ? "current.txt" : "current_" + day + ".txt";
            writeFile(new File(DIR, name), sb.toString());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " writeCurrent error: " + t);
        }
    }

    private static Object call(Object o, String name) throws Exception {
        if (o == null) return null;
        Method m = o.getClass().getMethod(name);
        return m.invoke(o);
    }

    private static long dayStart(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long todayStart() {
        return dayStart(System.currentTimeMillis());
    }

    private static void writeFile(File f, String s) throws Exception {
        File dir = f.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(s.getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            tmp.renameTo(f);
        }
    }
}
