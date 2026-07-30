import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const javaPath = path.join(
  root,
  "android/app/src/main/java/com/VocoCraft/VocoCraft/CaseRewardManager.java",
);
const luaPath = path.join(
  root,
  "games/vococraft/mods/PLAYER/vococraft_rewarded_ads/init.lua",
);

const java = fs.readFileSync(javaPath, "utf8");
const lua = fs.readFileSync(luaPath, "utf8");

const javaRewards = [...java.matchAll(
  /new Prize\(\s*(\d+),\s*"([^"]+)",\s*"[^"]+",\s*(\d+),\s*(\d+),/g,
)].map((match) => ({
  index: Number(match[1]),
  itemId: match[2],
  count: Number(match[3]),
  weight: Number(match[4]),
}));

const luaRewards = [...lua.matchAll(
  /\[(\d+)\]\s*=\s*\{stack\s*=\s*"([^"]+)",\s*weight\s*=\s*(\d+)\}/g,
)].map((match) => {
  const parts = match[2].trim().split(/\s+/);
  return {
    index: Number(match[1]),
    itemId: parts[0],
    count: parts[1] ? Number(parts[1]) : 1,
    weight: Number(match[3]),
  };
});

function fail(message) {
  console.error(`FAIL: ${message}`);
  process.exitCode = 1;
}

if (javaRewards.length !== 17) {
  fail(`expected 17 Java rewards, found ${javaRewards.length}`);
}
if (luaRewards.length !== javaRewards.length) {
  fail(`Java/Lua reward count differs: ${javaRewards.length}/${luaRewards.length}`);
}

const totalWeight = javaRewards.reduce((sum, reward) => sum + reward.weight, 0);
if (totalWeight !== 10_000) {
  fail(`weights must total 10,000 bp, found ${totalWeight}`);
}

for (let index = 0; index < javaRewards.length; index += 1) {
  const android = javaRewards[index];
  const game = luaRewards.find((reward) => reward.index === index);
  if (!game) {
    fail(`Lua reward ${index} is missing`);
    continue;
  }
  for (const field of ["itemId", "count", "weight"]) {
    if (android[field] !== game[field]) {
      fail(`reward ${index} ${field} differs: ${android[field]} / ${game[field]}`);
    }
  }
}

if (!process.exitCode) {
  console.log(`PASS: ${javaRewards.length} rewards, total weight ${totalWeight} bp`);
  for (const reward of javaRewards) {
    console.log(
      `${String(reward.index).padStart(2, "0")}  ${String(reward.weight).padStart(4)} bp  ` +
      `${reward.itemId}${reward.count > 1 ? ` x${reward.count}` : ""}`,
    );
  }
}
