// GeneralsX @feature Android port 10/07/2026 A couple of globals that the
// ported GeneralsOnline interfaces (OnlineServices_LobbyInterface.cpp,
// NGMP_Helpers.cpp) expect to find defined elsewhere -- in upstream
// SuperHackers_GO they live in the desktop WOL-lobby menu source
// (WOLGameSetupMenu.cpp), which this port deliberately does not carry over
// (it's a full rewrite of the legacy .wnd-based multiplayer UI, out of scope
// for this first pass; our own lobby screen, once built, will replace it).
// This file is NOT ported from upstream -- it's our own minimal stand-in so
// the rest of the module links.

#include "GameNetwork/GeneralsOnline/NGMP_interfaces.h"
#include "GameNetwork/GeneralsOnline/GeneralsOnline_AndroidGlue.h"
#include "GameNetwork/GeneralsOnline/OnlineServices_Auth.h"
#include "GameNetwork/GameSpyOverlay.h"
#include <cstdlib>
#include <string>

#if defined(__ANDROID__)
#include <SDL3/SDL.h>
#include <cstdio>
#include <cstring>
#endif

NGMPGame* TheNGMPGame = nullptr;

void OnKickedFromLobby()
{
	if (TheNGMPGame != nullptr)
	{
		TheNGMPGame->reset();
	}

	NetworkLog(ELogVerbosity::LOG_RELEASE, "[GeneralsOnline] Kicked from lobby");

	// TODO_GO_ANDROID: once a lobby-browser screen exists, this should also
	// navigate the player back out of it (mirrors upstream's nextScreen +
	// TheShell->pop() in WOLGameSetupMenu.cpp's OnKickedFromLobby()).
}

#if defined(__ANDROID__)
namespace
{
	struct AndroidSession
	{
		std::string sessionToken;
		std::string userId;
		std::string displayName;
		std::string wsUri;
	};

	// Mirrors SDL3Main.cpp's gamedata_path.txt reader: the Android launcher
	// (GeneralsOnlineActivity.java) writes this plain "key=value" marker file
	// straight into getFilesDir() -- the same directory
	// SDL_GetAndroidInternalStoragePath() resolves to -- there is no JNI
	// plumbing involved on either side, just a shared filesystem convention.
	bool ReadAndroidSession(AndroidSession& outSession)
	{
		const char* internalPath = SDL_GetAndroidInternalStoragePath();
		if (internalPath == nullptr)
		{
			return false;
		}

		char markerPath[1024];
		snprintf(markerPath, sizeof(markerPath), "%s/generalsonline_session.txt", internalPath);
		FILE* f = fopen(markerPath, "r");
		if (f == nullptr)
		{
			return false;
		}

		char line[2048];
		while (fgets(line, sizeof(line), f) != nullptr)
		{
			size_t len = strlen(line);
			while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r'))
			{
				line[--len] = '\0';
			}

			char* eq = strchr(line, '=');
			if (eq == nullptr)
			{
				continue;
			}
			*eq = '\0';
			const char* key = line;
			const char* value = eq + 1;

			if (strcmp(key, "session_token") == 0) outSession.sessionToken = value;
			else if (strcmp(key, "user_id") == 0) outSession.userId = value;
			else if (strcmp(key, "display_name") == 0) outSession.displayName = value;
			else if (strcmp(key, "ws_uri") == 0) outSession.wsUri = value;
		}
		fclose(f);

		return !outSession.sessionToken.empty() && !outSession.wsUri.empty();
	}
}
#endif // __ANDROID__

bool TryStartGeneralsOnline()
{
#if defined(__ANDROID__)
	AndroidSession session;
	if (!ReadAndroidSession(session))
	{
		// Not signed in yet -- the player needs to use the GeneralsOnline
		// Account screen in the Settings app first. Fall through to the
		// legacy path (which will show its own "can't connect" message);
		// a friendlier prompt here is a follow-up.
		return false;
	}

	if (NGMP_OnlineServicesManager::GetInstance() == nullptr)
	{
		NGMP_OnlineServicesManager::CreateInstance();
		NGMP_OnlineServicesManager::GetInstance()->Init();
	}

	// GeneralsX @bugfix Android port 10/07/2026 the launcher already exchanged
	// its device code for a session token before the game even started; that
	// token was being read from the marker file and then silently discarded
	// here, so NGMP_OnlineServices_AuthInterface::IsLoggedIn() stayed false
	// and every authenticated call (GetFriendsList/GetBlockList, fired right
	// after WS connect below) went out with no Authorization header and got
	// rejected 401 by the server (confirmed via a real device log).
	NGMP_OnlineServices_AuthInterface* pAuthInterface = NGMP_OnlineServicesManager::GetInterface<NGMP_OnlineServices_AuthInterface>();
	if (pAuthInterface != nullptr)
	{
		int64_t userID = static_cast<int64_t>(std::strtoll(session.userId.c_str(), nullptr, 10));
		pAuthInterface->SetExternalSession(session.sessionToken, userID, session.displayName);
	}

	ClearGSMessageBoxes();
	GSMessageBoxNoButtons(UnicodeString(L"GeneralsOnline"), UnicodeString(L"Connecting..."), false);

	std::string displayName = session.displayName;
	NGMP_OnlineServicesManager::GetInstance()->OnLogin(ELoginResult::Success, session.wsUri.c_str(), [displayName]()
		{
			ClearGSMessageBoxes();
			UnicodeString msg;
			msg.format(UnicodeString(L"Connected to GeneralsOnline as %hs.\n\n(Lobby browsing isn't wired up in the game client yet.)"), displayName.c_str());
			GSMessageBoxOk(UnicodeString(L"GeneralsOnline"), msg, nullptr);
		});

	return true;
#else
	return false;
#endif
}
