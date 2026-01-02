-- Vococraft
-- Content filter for Russian Federation compliance
-- SPDX-License-Identifier: LGPL-2.1-or-later

-- This filter excludes content that violates Russian Federation legislation
-- Last updated: 2025

-- Keywords that indicate prohibited content (case-insensitive)
-- Categories based on Russian Federation laws as of 2025

local content_filter = {}

-- Prohibited keywords list
-- These are checked against package name, title, author, and description
-- Use simple base words to catch all variations
content_filter.prohibited_keywords = {
	-- LGBT+ related (Federal Law 478-FZ)
	"pride",
	"lgbt",
	"gay",
	"queer",
	"transgender",
	"transsexual",
	"nonbinary",
	"genderfluid",
	"genderqueer",
	"agender",
	"bigender",
	"pansexual",
	"polysexual",
	"omnisexual",
	"intersex",
	"asexual",
	"aromantic",
	"sapphic",
	"achillean",
	"homosexual",
	"bisexual",
	"lesbian",
	"enby",
	"deadname",
	"neopronoun",
	"drag queen",
	"drag king",
	"stonewall",
	"dyke",
	"faggot",
	
	-- Otherkin/Furry (LGBT+ adjacent)
	"otherkin",
	"therian",
	"fursona",
	"fursuit",
	"yiff",
	"zoophil",
	
	-- Extremism
	"nazi",
	"swastika",
	"hitler",
	"heil",
	"reich",
	"totenkopf",
	"supremacy",
	"aryan",
	"kkk",
	"ku klux",
	"isis",
	"daesh",
	"qaeda",
	"terrorist",
	"terrorism",
	"jihad",
	"mujahideen",
	"hamas",
	"hezbollah",
	"azov",
	"bandera",
	"fascis",
	"genocide",
	"holocau",
	"auschwitz",
	"1488",
	"sonnenrad",
	"wolfsangel",
	
	-- Drugs
	"cannabis",
	"marijuana",
	"cocaine",
	"heroin",
	"meth",
	"amphetamine",
	"lsd",
	"psychedelic",
	"psilocybin",
	"mdma",
	"ecstasy",
	"ketamine",
	"opium",
	"opioid",
	"fentanyl",
	"420",
	
	-- Suicide/self-harm
	"suicide",
	"self-harm",
	"selfharm",
	"kys",
	"pro-ana",
	"pro-mia",
	"thinspo",
	
	-- Child exploitation
	"pedo",
	"loli",
	"shota",
	"jailbait",
	
	-- Childfree (2024 law)
	"childfree",
	"antinatalis",
	
	-- Religious extremism
	"satanic",
	"devil worship",
	"blasphemy",
	"islamophob",
	"christophob",
}

-- Also filter by package ID/name patterns
-- Simple base words that catch all variations
content_filter.prohibited_package_ids = {
	"pride",
	"lgbt",
	"gay",
	"queer",
	"lesbian",
	"bisexual",
	"pansexual",
	"nonbinary",
	"transgender",
	"otherkin",
	"therian",
	"furry",
}

-- Check if a string contains any prohibited keyword
function content_filter.contains_prohibited(text)
	if not text then
		return false, nil
	end
	
	local lower_text = text:lower()
	
	for _, keyword in ipairs(content_filter.prohibited_keywords) do
		if lower_text:find(keyword:lower(), 1, true) then
			return true, keyword
		end
	end
	
	return false, nil
end

-- Check if a package should be filtered out
-- Returns true if package should be HIDDEN
function content_filter.should_filter_package(package)
	if not package then
		return false
	end
	
	-- Check package ID against prohibited patterns
	if package.id then
		local lower_id = package.id:lower()
		for _, pattern in ipairs(content_filter.prohibited_package_ids) do
			if lower_id:find(pattern:lower(), 1, true) then
				core.log("info", "[Vococraft] Filtered package '" .. 
					package.id .. "' due to prohibited ID pattern: " .. pattern)
				return true
			end
		end
	end
	
	-- Check package name separately (may differ from ID)
	if package.name then
		local lower_name = package.name:lower()
		for _, pattern in ipairs(content_filter.prohibited_package_ids) do
			if lower_name:find(pattern:lower(), 1, true) then
				core.log("info", "[Vococraft] Filtered package '" .. 
					(package.name) .. "' due to prohibited name pattern: " .. pattern)
				return true
			end
		end
	end
	
	-- Check all relevant text fields
	local fields_to_check = {
		package.name,
		package.title,
		package.author,
		package.short_description,
	}
	
	for _, field in ipairs(fields_to_check) do
		local prohibited, keyword = content_filter.contains_prohibited(field)
		if prohibited then
			core.log("info", "[Vococraft] Filtered package '" .. 
				(package.title or package.name or "unknown") .. 
				"' due to prohibited content: " .. keyword)
			return true
		end
	end
	
	return false
end

-- Make globally accessible
vococraft_content_filter = content_filter

return content_filter
