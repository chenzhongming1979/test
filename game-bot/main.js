// ==================== 配置区 ====================
const CONFIG = {
    // 坐标偏移范围（避免被检测为固定点击）
    clickJitter: [2, 5],
    
    // 每次操作的延迟（毫秒），随机化防检测
    minDelay: 800,
    maxDelay: 2000,
    
    // 找图超时时间
    findTimeout: 10000,
    
    // 循环次数（0=无限循环）
    loopCount: 0,
};

// ==================== 工具函数 ====================

/** 随机延迟 */
function randomDelay() {
    const delay = random(CONFIG.minDelay, CONFIG.maxDelay);
    sleep(delay);
}

/** 随机偏移后点击 */
function randomClick(x, y) {
    const dx = random(-CONFIG.clickJitter[0], CONFIG.clickJitter[1]);
    const dy = random(-CONFIG.clickJitter[0], CONFIG.clickJitter[1]);
    tap(x + dx, y + dy);
    randomDelay();
}

/** 等待图片出现（带超时） */
function waitForImage(templatePath, timeout = CONFIG.findTimeout) {
    let elapsed = 0;
    while (elapsed < timeout) {
        const pos = findImage(templatePath);
        if (pos) return pos;
        sleep(500);
        elapsed += 500;
    }
    return null;
}

/** 在指定区域内找图 */
function findImage(templatePath, region) {
    const img = images.read(files.path(templatePath));
    if (!img) {
        console.error(`找不到模板文件: ${templatePath}`);
        return null;
    }
    
    const r = region || { x: 0, y: 0, w: device.width, h: device.height };
    const screen = captureScreen();
    
    const result = images.matchTemplate(screen, img, {
        threshold: 0.8,    // 相似度阈值
        region: [r.x, r.y, r.w, r.h]
    });
    
    img.recycle();
    screen.recycle();
    
    return result ? result.bestMatch : null;
}

// ==================== 页面识别器 ====================

/** 判断当前是什么页面 */
function identifyPage() {
    // 优先检查战斗画面
    const battlePos = findImage("/storage/emulated/0/autojs/images/battle_bg.png", 
        { x: 0, y: 0, w: device.width, h: device.height * 0.7 });
    if (battlePos) return "battle";
    
    // 再检查主界面
    const menuPos = findImage("/storage/emulated/0/autojs/images/btn_start.png");
    if (menuPos) return "main_menu";
    
    // 检查背包页
    const bagPos = findImage("/storage/emulated/0/autojs/images/btn_backpack.png");
    if (bagPos) return "inventory";
    
    // 兜底：检查特定颜色（如血条颜色）
    const centerX = Math.floor(device.width / 2);
    const centerY = Math.floor(device.height * 0.1);
    const color = colors.toString(colors.pixel(centerX, centerY));
    if (color.includes("FF0000")) return "battle"; // 红色可能是战斗中的血条
    
    return "unknown";
}

// ==================== 核心动作逻辑 ====================

/** 从主界面开始挂机 */
function startFarm() {
    console.show();
    console.log("==== 开始挂机 ====");
    
    let count = 0;
    
    do {
        const page = identifyPage();
        
        switch (page) {
            case "main_menu":
                console.log("在主菜单，点击开始...");
                const pos = findImage("/storage/emulated/0/autojs/images/btn_start.png");
                if (pos) {
                    randomClick(pos.x, pos.y);
                    sleep(3000); // 等待进入下一关
                } else {
                    console.warn("未找到开始按钮，尝试手动介入");
                    return false;
                }
                break;
                
            case "battle":
                console.log("战斗中，自动处理...");
                handleBattle();
                break;
                
            case "inventory":
                console.log("背包页，关闭并返回...");
                back();
                randomDelay();
                break;
                
            default:
                console.warn(`未知页面: ${page}`);
                sleep(2000);
        }
        
        count++;
        console.log(`完成第 ${count} 轮`);
        
    } while (CONFIG.loopCount === 0 || count < CONFIG.loopCount);
    
    console.log("==== 挂机结束 ====");
    return true;
}

/** 战斗逻辑：根据血条状态行动 */
function handleBattle() {
    // 示例：检查是否有药水瓶可用
    const potionPos = findImage("/storage/emulated/0/autojs/images/item_potion.png");
    if (potionPos) {
        console.log("血量不足，使用药水");
        randomClick(potionPos.x, potionPos.y);
        sleep(1500);
    }
    
    // 攻击按钮
    const attackPos = findImage("/storage/emulated/0/autojs/images/btn_attack.png");
    if (attackPos) {
        randomClick(attackPos.x, attackPos.y);
    }
    
    // 检测战斗是否结束
    let idle = 0;
    while (idle < 30) {
        const newPage = identifyPage();
        if (newPage !== "battle") {
            return; // 战斗结束
        }
        sleep(1000);
        idle++;
    }
}

// ==================== 入口 ====================

// 需要在Auto.js中设置为"前台服务"运行
events.observeNotification();
events.onToast(function(toast) {
    console.log(`[Toast] ${toast.getText()}`);
});

startFarm();
