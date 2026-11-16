import asyncio, sys, json
from playwright.async_api import async_playwright

CHROME = "/home/agentuser/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome"
BASE = "http://127.0.0.1:8453/index.html"
SHOTS = "/home/agentuser/NexusClone/mockup/shots"
SCREENS = ["clones", "detail", "apps", "proxy", "diag", "settings"]

async def main():
    errors = []
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=CHROME, args=["--no-sandbox"])
        ctx = await b.new_context(viewport={"width": 1180, "height": 940}, device_scale_factor=2)
        pg = await ctx.new_page()
        pg.on("console", lambda m: errors.append(f"console.{m.type}: {m.text}") if m.type == "error" else None)
        pg.on("pageerror", lambda e: errors.append(f"pageerror: {e}"))

        await pg.goto(BASE, wait_until="networkidle")

        for theme in ("dark", "light"):
            await pg.evaluate(f"document.documentElement.setAttribute('data-theme','{theme}')")
            for name in SCREENS:
                await pg.click(f'.rail-btn[data-go="{name}"]')
                await pg.wait_for_timeout(220)
                vis = await pg.evaluate(
                    "(n)=>{const el=document.getElementById('s-'+n);"
                    "const r=el.getBoundingClientRect();"
                    "return {on:el.classList.contains('on'),w:Math.round(r.width),h:Math.round(r.height)}}",
                    name,
                )
                if not vis["on"] or vis["w"] < 300:
                    errors.append(f"screen {name} not visible: {vis}")
                await pg.locator("#device").screenshot(path=f"{SHOTS}/{theme}-{name}.png")

        await pg.evaluate("document.documentElement.setAttribute('data-theme','dark')")
        await pg.click('.rail-btn[data-go="clones"]')
        await pg.wait_for_timeout(150)
        metrics = await pg.evaluate(
            "()=>{const d=document.getElementById('device');"
            "const b=document.querySelector('#s-clones .body');"
            "return {docW:document.documentElement.scrollWidth,winW:window.innerWidth,"
            "devW:Math.round(d.getBoundingClientRect().width),"
            "devH:Math.round(d.getBoundingClientRect().height),"
            "bodyScroll:b.scrollHeight,bodyClient:b.clientHeight}}"
        )
        if metrics["docW"] > metrics["winW"] + 1:
            errors.append(f"horizontal overflow: {metrics}")

        await ctx.close()
        ctx2 = await b.new_context(viewport={"width": 390, "height": 844}, device_scale_factor=2)
        pg2 = await ctx2.new_page()
        pg2.on("pageerror", lambda e: errors.append(f"phone pageerror: {e}"))
        await pg2.goto(BASE, wait_until="networkidle")
        await pg2.wait_for_timeout(200)
        await pg2.screenshot(path=f"{SHOTS}/phone-clones.png")
        pm = await pg2.evaluate(
            "()=>({docW:document.documentElement.scrollWidth,winW:window.innerWidth,"
            "devW:Math.round(document.getElementById('device').getBoundingClientRect().width)})"
        )
        if pm["docW"] > pm["winW"] + 1:
            errors.append(f"phone horizontal overflow: {pm}")

        await b.close()

    print(json.dumps({"errors": errors, "metrics": metrics, "phone": pm}, indent=2))
    return 1 if errors else 0

sys.exit(asyncio.run(main()))
