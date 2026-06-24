(() => {
    // Увесь код інтерфейсу ізольований у функції, щоб не засмічувати глобальний об’єкт window.

    // Ключі localStorage/sessionStorage для збереження стану інтерфейсу між перезавантаженнями сторінки.
    const SESSION_TOKEN_KEY = "onlineChatToken";
    const SIDEBAR_WIDTH_KEY = "onlineChatSidebarWidth";
    localStorage.removeItem(SESSION_TOKEN_KEY);
    const API_BASE_URL = String(window.CHAT_API_URL || "").replace(/\/+$/, "");

    // Глобальний стан клієнта: поточний користувач, список чатів, вибраний чат, повідомлення, аватарки та підключення до подій.
    const state = {
        token: sessionStorage.getItem(SESSION_TOKEN_KEY),
        currentUser: null,
        users: [],
        friends: [],
        friendSearchResults: [],
        friendSearchVersion: 0,
        chats: [],
        selected: null,
        currentMessages: [],
        replyTo: null,
        selectedMessageAction: null,
        selectedReactionMessage: null,
        profileAvatarDataUrl: null,
        profileRemoveAvatar: false,
        groupAvatarDataUrl: null,
        groupRemoveAvatar: false,
        avatarObjectUrl: null,
        viewProfileAvatarUrl: null,
        peerAvatarUrls: new Map(),
        peerAvatarLoading: new Set(),
        peerAvatarUnavailable: new Set(),
        eventController: null,
        eventReconnectTimer: null,
        refreshTimer: null,
        lastTypingSentAt: 0,
        theme: localStorage.getItem("onlineChatTheme") || "light"
    };

    // Кольори допомагають візуально відрізняти авторів повідомлень у групових чатах.
    const SENDER_COLORS = ["#087fca", "#0c9a8e", "#4777d1", "#b06818", "#9a4eb0", "#be4a67"];
    const REACTION_OPTIONS = ["❤️", "👍", "😂", "😢", "🔥", "😮", "👏"];
    const $ = (id) => document.getElementById(id);
    const authScreen = $("authScreen");
    const appScreen = $("appScreen");
    const authForm = $("authForm");
    const usernameInput = $("usernameInput");
    const passwordInput = $("passwordInput");
    const authError = $("authError");
    const authModeButton = $("authModeButton");
    const authTitle = $("authTitle");
    const authDescription = $("authDescription");
    const authSubmit = $("authSubmit");
    const chatList = $("chatList");
    const messageList = $("messageList");
    const messageInput = $("messageInput");
    const messageSearch = $("messageSearch");
    const sendButton = $("sendButton");
    const conversationTitle = $("conversationTitle");
    const conversationStatus = $("conversationStatus");
    const typingIndicator = $("typingIndicator");
    const chatActionsButton = $("chatActionsButton");
    const groupDialog = $("groupDialog");
    const membersDialog = $("membersDialog");
    const friendsDialog = $("friendsDialog");
    const chatActionsDialog = $("chatActionsDialog");
    const messageActionsDialog = $("messageActionsDialog");
    const profileDialog = $("profileDialog");
    const userProfileDialog = $("userProfileDialog");
    const adminDialog = $("adminDialog");
    let registrationMode = false;

    /**
     * Перемикає світлу або темну тему та зберігає вибір у браузері.
     */
    function setTheme(theme) {
        state.theme = theme;
        document.documentElement.dataset.theme = theme;
        localStorage.setItem("onlineChatTheme", theme);
        $("themeButton").textContent = theme === "light" ? "Dark" : "Light";
    }

    /**
     * Спільна функція для запитів до backend API.
     * Вона автоматично додає JWT-токен і обробляє помилки авторизації.
     */
    async function api(path, options = {}) {
        const headers = { ...(options.body ? { "Content-Type": "application/json" } : {}), "ngrok-skip-browser-warning": "true", ...(options.headers || {}) };
        if (state.token) headers.Authorization = `Bearer ${state.token}`;
        const response = await fetch(apiUrl(path), { ...options, headers });
        const payload = await response.json().catch(() => ({}));
        if (!response.ok) {
            if (response.status === 401 && state.token) logout();
            throw new Error(payload.error || "Request failed");
        }
        return payload;
    }

    function apiUrl(path) {
        return `${API_BASE_URL}${path}`;
    }

    function showToast(text) {
        const toast = $("toast");
        toast.textContent = text;
        toast.classList.remove("hidden");
        window.clearTimeout(showToast.timeout);
        showToast.timeout = window.setTimeout(() => toast.classList.add("hidden"), 2600);
    }

    function isAdmin() {
        return state.currentUser && state.currentUser.role === "ADMIN";
    }

    function profileInitial(username) {
        return String(username || "?").trim().slice(0, 1).toUpperCase() || "?";
    }

    /**
     * Оновлює короткий блок профілю в лівій панелі: ім’я, опис і аватарку.
     */
    function renderProfileSummary() {
        const user = state.currentUser;
        $("profileUsername").textContent = user ? user.username : "Profile";
        $("profileDescription").textContent = user?.description || "Set your avatar and description";
        const avatar = $("profileAvatar");
        avatar.replaceChildren();
        if (state.avatarObjectUrl) {
            const image = document.createElement("img");
            image.src = state.avatarObjectUrl;
            image.alt = "";
            avatar.append(image);
        } else {
            avatar.textContent = profileInitial(user?.username);
        }
    }

    /**
     * Завантажує аватарку поточного користувача як Blob і показує її через object URL.
     */
    async function loadProfileAvatar() {
        if (!state.currentUser?.hasAvatar) {
            if (state.avatarObjectUrl) URL.revokeObjectURL(state.avatarObjectUrl);
            state.avatarObjectUrl = null;
            renderProfileSummary();
            return;
        }
        try {
            const response = await fetch(apiUrl("/api/me/avatar"), {
                headers: { Authorization: `Bearer ${state.token}`, "ngrok-skip-browser-warning": "true" }
            });
            if (!response.ok) throw new Error("Avatar unavailable");
            const image = await response.blob();
            if (state.avatarObjectUrl) URL.revokeObjectURL(state.avatarObjectUrl);
            state.avatarObjectUrl = URL.createObjectURL(image);
        } catch (error) {
            if (state.avatarObjectUrl) URL.revokeObjectURL(state.avatarObjectUrl);
            state.avatarObjectUrl = null;
        }
        renderProfileSummary();
    }

    function clearPeerAvatars() {
        state.peerAvatarUrls.forEach((url) => URL.revokeObjectURL(url));
        state.peerAvatarUrls.clear();
        state.peerAvatarLoading.clear();
        state.peerAvatarUnavailable.clear();
    }

    function avatarCacheKey(chat) {
        return `${chat.type}:${chat.key}`;
    }

    /**
     * Створює HTML-вузол аватарки для елемента списку чатів або шапки діалогу.
     */
    function peerAvatarNode(chat, extraClass = "") {
        const avatar = document.createElement("span");
        avatar.className = `chat-avatar${extraClass ? ` ${extraClass}` : ""}${chat.type === "group" ? " group-avatar" : ""}`;
        const source = state.peerAvatarUrls.get(avatarCacheKey(chat));
        if (source) {
            const image = document.createElement("img");
            image.src = source;
            image.alt = "";
            avatar.append(image);
        } else {
            avatar.textContent = chat.type === "group" ? "#" : profileInitial(chat.title);
        }
        return avatar;
    }

    /**
     * Ліниво завантажує аватарку співрозмовника або групи й кешує її в пам’яті.
     */
    function ensurePeerAvatar(chat) {
        if (!chat || (chat.type !== "private" && chat.type !== "group")) return;
        const key = avatarCacheKey(chat);
        if (!chat.hasAvatar) {
            const existing = state.peerAvatarUrls.get(key);
            if (existing) URL.revokeObjectURL(existing);
            state.peerAvatarUrls.delete(key);
            state.peerAvatarUnavailable.delete(key);
            return;
        }
        if (state.peerAvatarUrls.has(key) || state.peerAvatarLoading.has(key) || state.peerAvatarUnavailable.has(key)) {
            return;
        }
        state.peerAvatarLoading.add(key);
        const avatarPath = chat.type === "group"
                ? `/api/groups/${encodeURIComponent(chat.key)}/avatar`
                : `/api/users/${encodeURIComponent(chat.key)}/avatar`;
        fetch(apiUrl(avatarPath), {
            headers: { Authorization: `Bearer ${state.token}`, "ngrok-skip-browser-warning": "true" }
        }).then(async (response) => {
            if (!response.ok) throw new Error("Avatar unavailable");
            return response.blob();
        }).then((image) => {
            const previous = state.peerAvatarUrls.get(key);
            if (previous) URL.revokeObjectURL(previous);
            state.peerAvatarUrls.set(key, URL.createObjectURL(image));
            renderChats();
            updateConversationHeader();
            renderGroupAvatarEditor();
        }).catch(() => {
            state.peerAvatarUnavailable.add(key);
        }).finally(() => state.peerAvatarLoading.delete(key));
    }

    function setAuthMode(register) {
        registrationMode = register;
        authTitle.textContent = register ? "Create account" : "Online Chat";
        authDescription.textContent = register ? "Choose a username and password to start chatting." : "Sign in to continue your conversations.";
        authSubmit.textContent = register ? "Create account" : "Sign in";
        authModeButton.textContent = register ? "I already have an account" : "Create an account";
        passwordInput.autocomplete = register ? "new-password" : "current-password";
        authError.textContent = "";
    }

    /**
     * Виконує вхід або реєстрацію залежно від поточного режиму форми.
     */
    async function authenticate(event) {
        event.preventDefault();
        authError.textContent = "";
        try {
            const response = await api(registrationMode ? "/api/register" : "/api/login", {
                method: "POST",
                body: JSON.stringify({ username: usernameInput.value.trim(), password: passwordInput.value })
            });
            state.token = response.token;
            sessionStorage.setItem(SESSION_TOKEN_KEY, state.token);
            passwordInput.value = "";
            await openApp();
        } catch (error) {
            authError.textContent = error.message;
        }
    }

    /**
     * Запускає основний екран після авторизації: профіль, чати, події та періодичне оновлення.
     */
    async function openApp() {
        state.currentUser = await api("/api/me");
        $("accountLabel").textContent = `${state.currentUser.username} - ${state.currentUser.role}`;
        $("adminButton").classList.toggle("hidden", !isAdmin());
        authScreen.classList.add("hidden");
        appScreen.classList.remove("hidden");
        await loadProfileAvatar();
        await refreshChats();
        connectEventStream();
    }

    function logout() {
        state.eventController?.abort();
        state.eventController = null;
        window.clearTimeout(state.eventReconnectTimer);
        window.clearTimeout(state.refreshTimer);
        state.eventReconnectTimer = null;
        state.refreshTimer = null;
        state.token = null;
        state.currentUser = null;
        state.users = [];
        state.friends = [];
        state.friendSearchResults = [];
        state.chats = [];
        state.selected = null;
        state.currentMessages = [];
        state.replyTo = null;
        state.selectedMessageAction = null;
        state.selectedReactionMessage = null;
        closeReactionMenu();
        state.profileAvatarDataUrl = null;
        state.profileRemoveAvatar = false;
        state.groupAvatarDataUrl = null;
        state.groupRemoveAvatar = false;
        if (state.avatarObjectUrl) URL.revokeObjectURL(state.avatarObjectUrl);
        state.avatarObjectUrl = null;
        if (state.viewProfileAvatarUrl) URL.revokeObjectURL(state.viewProfileAvatarUrl);
        state.viewProfileAvatarUrl = null;
        clearPeerAvatars();
        sessionStorage.removeItem(SESSION_TOKEN_KEY);
        appScreen.classList.add("hidden");
        authScreen.classList.remove("hidden");
        chatList.replaceChildren();
        messageList.replaceChildren();
        conversationTitle.textContent = "Choose a chat";
        conversationStatus.textContent = "Select a user or group on the left.";
        typingIndicator.textContent = "";
        messageInput.disabled = true;
        messageSearch.disabled = true;
        sendButton.disabled = true;
        renderProfileSummary();
        $("adminButton").classList.add("hidden");
    }

    /**
     * Відкриває SSE-підключення до /api/events для отримання оновлень без ручного перезавантаження.
     */
    async function connectEventStream() {
        if (!state.token) return;
        state.eventController?.abort();
        const controller = new AbortController();
        state.eventController = controller;
        try {
            const response = await fetch(apiUrl("/api/events"), {
    headers: {
        Authorization: `Bearer ${state.token}`,
        "ngrok-skip-browser-warning": "true"
    },
    signal: controller.signal
});
            if (!response.ok || !response.body) {
                throw new Error("Event stream is unavailable");
            }
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = "";
            while (!controller.signal.aborted) {
                const chunk = await reader.read();
                if (chunk.done) break;
                buffer += decoder.decode(chunk.value, { stream: true });
                const parts = buffer.split("\n\n");
                buffer = parts.pop();
                parts.forEach(handleEventChunk);
            }
            if (!controller.signal.aborted && state.token) {
                scheduleEventReconnect();
            }
        } catch (error) {
            if (!controller.signal.aborted && state.token) {
                scheduleEventReconnect();
            }
        }
    }

    function handleEventChunk(chunk) {
        const data = chunk.split("\n")
            .filter((line) => line.startsWith("data: "))
            .map((line) => line.substring(6))
            .join("\n");
        if (!data) return;
        try {
            handleServerEvent(JSON.parse(data));
        } catch (error) {
            // A malformed event must not interrupt the persistent connection.
        }
    }

    /**
     * Реагує на серверні події: нові повідомлення, редагування, реакції, статуси та зміни груп.
     */
    function handleServerEvent(event) {
        if (event.command === "EVENT_TYPING") {
            void loadTyping(state.selected);
            return;
        }

        const action = event.fields?.action;
        const group = event.fields?.group;
        const selectedGroupChanged = state.selected?.type === "group" && state.selected.key === group;

        if (event.command === "EVENT_MESSAGE_UPDATE" && action === "group-avatar") {
            clearGroupAvatarCache(group);
            selectedGroupChanged ? void refreshGroupState() : scheduleRefresh();
            return;
        }

        if (event.command === "EVENT_MESSAGE_UPDATE" && ["group-members", "group-admins"].includes(action)) {
            selectedGroupChanged ? void refreshGroupState() : scheduleRefresh();
            return;
        }

        if (event.command === "EVENT_STATUS" && event.fields?.state === "profile-updated") {
            clearUserAvatarCache(event.fields.username);
        }

        if (event.command) scheduleRefresh();
    }

    function clearGroupAvatarCache(group) {
        state.chats.filter((chat) => chat.type === "group" && chat.key === group).forEach((chat) => {
            const key = avatarCacheKey(chat);
            const avatar = state.peerAvatarUrls.get(key);
            if (avatar) URL.revokeObjectURL(avatar);
            state.peerAvatarUrls.delete(key);
            state.peerAvatarUnavailable.delete(key);
        });
    }

    function clearUserAvatarCache(username) {
        if (!username) return;
        state.chats.filter((chat) => chat.type === "private" && chat.key === username).forEach((chat) => {
            const key = avatarCacheKey(chat);
            const avatar = state.peerAvatarUrls.get(key);
            if (avatar) URL.revokeObjectURL(avatar);
            state.peerAvatarUrls.delete(key);
            state.peerAvatarUnavailable.delete(key);
        });
        if (state.currentUser?.username === username) {
            void loadProfileAvatar();
        }
    }

    function scheduleRefresh() {
        if (state.refreshTimer || !state.token) return;
        state.refreshTimer = window.setTimeout(async () => {
            state.refreshTimer = null;
            try {
                await refreshChats();
            } catch (error) {
                showToast(error.message);
            }
        }, 80);
    }

    function scheduleEventReconnect() {
        if (state.eventReconnectTimer) return;
        state.eventReconnectTimer = window.setTimeout(() => {
            state.eventReconnectTimer = null;
            void connectEventStream();
        }, 1_500);
    }

    /**
     * Оновлює список чатів і синхронізує дані поточного користувача.
     */
    async function refreshChats() {
        const result = await api("/api/chats");
        state.currentUser = result.currentUser;
        state.users = result.users || [];
        state.friends = result.friends || [];
        state.chats = result.chats || [];
        state.chats.filter((chat) => !chat.hasAvatar).forEach((chat) => {
            const key = avatarCacheKey(chat);
            const avatar = state.peerAvatarUrls.get(key);
            if (avatar) URL.revokeObjectURL(avatar);
            state.peerAvatarUrls.delete(key);
            state.peerAvatarUnavailable.delete(key);
        });
        renderProfileSummary();
        const previous = state.selected;
        if (previous) {
            state.selected = state.chats.find((chat) => sameChat(chat, previous)) || null;
        }
        if (!state.selected && state.chats.length) {
            state.selected = state.chats[0];
        }
        renderChats();
        updateConversationHeader();
        if (state.selected) {
            await loadMessages();
        } else {
            state.currentMessages = [];
            renderMessages();
        }
    }

    async function refreshGroupState() {
        const wasGroup = state.selected?.type === "group";
        await refreshChats();
        if (wasGroup && state.selected?.type === "group" && membersDialog.open) {
            await loadGroupMembers();
        }
        updateConversationHeader();
    }

    function sameChat(first, second) {
        return first && second && first.type === second.type && first.key === second.key;
    }

    /**
     * Перемальовує ліву панель чатів з урахуванням пошуку та непрочитаних повідомлень.
     */
    function renderChats() {
        const filter = $("chatSearch").value.trim().toLowerCase();
        chatList.replaceChildren();
        const addSection = (title, type) => {
            const chats = state.chats.filter((chat) => chat.type === type && chatMatches(chat, filter));
            if (!chats.length) return;
            const heading = document.createElement("div");
            heading.className = "chat-section";
            heading.textContent = title;
            chatList.append(heading);
            chats.forEach((chat) => chatList.append(chatRow(chat)));
        };
        addSection("People", "private");
        addSection("GROUPS", "group");
        if (!chatList.children.length) {
            const empty = document.createElement("p");
            empty.className = "empty-state";
            empty.textContent = "No chats found.";
            chatList.append(empty);
        }
        $("chatCount").textContent = String(state.chats.length);
    }

    function chatMatches(chat, filter) {
        if (!filter) return true;
        return `${chat.title} ${chat.lastSender || ""} ${chat.lastText || ""}`.toLowerCase().includes(filter);
    }

    function chatRow(chat) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = `chat-row${sameChat(state.selected, chat) ? " selected" : ""}`;
        button.setAttribute("role", "option");
        button.setAttribute("aria-selected", sameChat(state.selected, chat) ? "true" : "false");

        const dot = document.createElement("span");
        dot.className = `online-dot${chat.online ? " online" : ""}`;
        const avatar = peerAvatarNode(chat);
        const text = document.createElement("span");
        const title = document.createElement("div");
        title.className = "chat-title";
        title.textContent = chat.title;
        const preview = document.createElement("div");
        preview.className = "chat-kind";
        preview.textContent = chatPreview(chat);
        text.append(title, preview);
        button.append(dot, avatar, text);
        if (chat.unreadCount > 0) {
            const unread = document.createElement("span");
            unread.className = "unread-badge";
            unread.textContent = chat.unreadCount > 99 ? "99+" : String(chat.unreadCount);
            button.append(unread);
        }
        button.addEventListener("click", () => selectChat(chat));
        ensurePeerAvatar(chat);
        return button;
    }

    function chatPreview(chat) {
        if (!chat.lastText) return chat.type === "group" ? "Group" : chat.online ? "Online" : "Offline";
        const prefix = chat.type === "group" && chat.lastSender && !chat.lastSystem ? `${chat.lastSender}: ` : "";
        return `${formatTime(new Date(chat.lastCreatedAt))} ${prefix}${chat.lastText}`.trim();
    }

    /**
     * Робить чат активним, завантажує його історію та оновлює шапку діалогу.
     */
    async function selectChat(chat) {
        state.selected = chat;
        clearReply();
        renderChats();
        updateConversationHeader();
        await loadMessages();
        messageInput.focus();
    }

    /**
     * Заповнює шапку діалогу: назву, статус, аватарку та доступні кнопки керування.
     */
    function updateConversationHeader() {
        const chat = state.selected;
        const enabled = Boolean(chat);
        messageInput.disabled = !enabled;
        messageSearch.disabled = !enabled;
        sendButton.disabled = !enabled;
        chatActionsButton.classList.toggle("hidden", !chat);
        const identity = $("conversationIdentity");
        identity.classList.toggle("profile-target", Boolean(chat && chat.type === "private"));
        identity.title = chat && chat.type === "private" ? "Open user profile" : "";
        const headerAvatar = $("conversationAvatar");
        headerAvatar.replaceChildren();
        headerAvatar.classList.toggle("hidden", !chat);
        if (!chat) {
            conversationTitle.textContent = "Choose a chat";
            conversationStatus.textContent = "Select a user or group on the left.";
            return;
        }
        const peerAvatar = peerAvatarNode(chat, "header-avatar");
        headerAvatar.className = peerAvatar.className;
        headerAvatar.replaceChildren(...peerAvatar.childNodes);
        ensurePeerAvatar(chat);
        conversationTitle.textContent = chat.title;
        conversationStatus.textContent = chat.type === "group" ? "Group chat" : chat.online ? "Online" : "Offline";
    }

    /**
     * Завантажує історію повідомлень для поточного чату.
     */
    async function loadMessages() {
        const selected = state.selected;
        if (!selected) return;
        const query = selected.type === "group" ? { group: selected.key } : { with: selected.key };
        const result = await api(`/api/messages?${new URLSearchParams(query)}`);
        if (!sameChat(selected, state.selected)) return;
        state.currentMessages = result.messages || [];
        renderMessages();
        await loadTyping(selected);
    }

    /**
     * Виводить повідомлення на екран і прокручує список до останнього повідомлення.
     */
    function renderMessages() {
        const shouldStick = messageList.scrollTop + messageList.clientHeight >= messageList.scrollHeight - 30;
        const filter = messageSearch.value.trim().toLowerCase();
        const messages = state.currentMessages.filter((message) => {
            return !filter || `${message.sender || ""} ${message.text || ""}`.toLowerCase().includes(filter);
        });
        messageList.replaceChildren();
        if (!messages.length) {
            const empty = document.createElement("p");
            empty.className = "empty-state";
            empty.textContent = filter ? "No messages match this search." : "No messages yet. Say hello.";
            messageList.append(empty);
        }
        messages.forEach((message) => messageList.append(messageNode(message)));
        if (shouldStick) messageList.scrollTop = messageList.scrollHeight;
    }

    /**
     * Формує HTML одного повідомлення: текст, автора, час, статус, відповідь і реакції.
     */
    function messageNode(message) {
        if (message.system) {
            return systemMessageNode(message);
        }
        const own = message.sender === state.currentUser.username;
        const row = document.createElement("article");
        row.className = `message-row${own ? " own" : ""}`;
        row.id = `message-${message.id}`;
        const bubble = document.createElement("div");
        bubble.className = "message-bubble";
        bubble.style.setProperty("--sender-color", senderColor(message.sender));
        if (state.selected.type === "group" && !own) {
            const author = document.createElement("button");
            author.type = "button";
            author.className = "message-author message-author-button";
            author.textContent = message.sender;
            author.addEventListener("click", (event) => {
                event.stopPropagation();
                void openUserProfile(message.sender);
            });
            bubble.append(author);
        }
        appendReplyQuote(bubble, message);
        const text = document.createElement("div");
        text.className = `message-text${message.edited ? " message-edited" : ""}`;
        text.textContent = message.text;
        const meta = document.createElement("div");
        meta.className = "message-meta";
        meta.textContent = messageMeta(message, own);
        bubble.append(text, meta);
        appendMessageReactions(bubble, message);
        if (!message.deleted) {
            attachMessageInteractionHandlers(bubble, message);
        }
        row.append(bubble);
        return row;
    }


    /**
     * Формує службове повідомлення групи: приєднання, вихід або видалення учасника.
     */
    function systemMessageNode(message) {
        const row = document.createElement("article");
        row.className = "system-message-row";
        row.id = `message-${message.id}`;
        const pill = document.createElement("span");
        pill.className = "system-message-pill";
        const time = formatTime(new Date(message.createdAt));
        pill.textContent = time ? `${message.text} · ${time}` : message.text;
        row.append(pill);
        return row;
    }


    /**
     * Прив’язує до повідомлення дії миші: подвійний клік, довге натискання та контекстне меню.
     */
    function attachMessageInteractionHandlers(bubble, message) {
        let longPressTimer = null;
        let longPressTriggered = false;
        let clickTimer = null;

        const clearLongPress = () => {
            window.clearTimeout(longPressTimer);
            longPressTimer = null;
        };

        bubble.addEventListener("pointerdown", (event) => {
            if (event.button && event.button !== 0) return;
            longPressTriggered = false;
            clearLongPress();
            longPressTimer = window.setTimeout(() => {
                longPressTriggered = true;
                openReactionMenu(message, event.clientX, event.clientY);
            }, 520);
        });
        ["pointerup", "pointerleave", "pointercancel"].forEach((name) => {
            bubble.addEventListener(name, clearLongPress);
        });
        bubble.addEventListener("click", () => {
            if (longPressTriggered) {
                longPressTriggered = false;
                return;
            }
            window.clearTimeout(clickTimer);
            clickTimer = window.setTimeout(() => openMessageActions(message), 210);
        });
        bubble.addEventListener("dblclick", (event) => {
            event.preventDefault();
            event.stopPropagation();
            window.clearTimeout(clickTimer);
            void reactToMessage(message, quickReaction());
        });
        bubble.addEventListener("contextmenu", (event) => {
            event.preventDefault();
            openReactionMenu(message, event.clientX, event.clientY);
        });
    }

    function appendMessageReactions(bubble, message) {
        const reactions = (message.reactions || []).filter((item) => item.count > 0);
        if (!reactions.length) return;
        const list = document.createElement("div");
        list.className = "message-reactions";
        reactions.forEach((item) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = `message-reaction${userReacted(message, item.reaction) ? " selected" : ""}`;
            button.textContent = `${item.reaction} ${item.count}`;
            button.title = (item.users || []).join(", ");
            button.addEventListener("click", (event) => {
                event.stopPropagation();
                void reactToMessage(message, item.reaction);
            });
            list.append(button);
        });
        bubble.append(list);
    }

    function userReacted(message, reaction) {
        const username = state.currentUser?.username;
        if (!username) return false;
        return (message.reactions || []).some((item) => item.reaction === reaction && (item.users || []).includes(username));
    }

    function quickReaction() {
        const reaction = state.currentUser?.quickReaction;
        return REACTION_OPTIONS.includes(reaction) ? reaction : "❤️";
    }

    /**
     * Відкриває меню реакцій біля позиції курсора.
     */
    function openReactionMenu(message, clientX, clientY) {
        state.selectedReactionMessage = message;
        messageActionsDialog.close();
        const menu = $("reactionMenu");
        menu.replaceChildren();
        REACTION_OPTIONS.forEach((reaction) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = userReacted(message, reaction) ? "selected" : "";
            button.textContent = reaction;
            button.title = reaction === quickReaction() ? "Your quick reaction" : "React";
            button.addEventListener("click", (event) => {
                event.stopPropagation();
                void reactToMessage(message, reaction);
            });
            menu.append(button);
        });
        menu.classList.remove("hidden");
        const margin = 10;
        const rect = menu.getBoundingClientRect();
        const left = Math.min(Math.max(clientX - rect.width / 2, margin), window.innerWidth - rect.width - margin);
        const top = Math.min(Math.max(clientY - rect.height - 12, margin), window.innerHeight - rect.height - margin);
        menu.style.left = `${left}px`;
        menu.style.top = `${top}px`;
    }

    function closeReactionMenu() {
        const menu = $("reactionMenu");
        if (!menu) return;
        menu.classList.add("hidden");
        state.selectedReactionMessage = null;
    }

    /**
     * Надсилає вибрану реакцію на сервер і локально оновлює повідомлення після відповіді API.
     */
    async function reactToMessage(message, reaction) {
        closeReactionMenu();
        try {
            const result = await api(`/api/messages/${message.id}/reactions`, {
                method: "POST",
                body: JSON.stringify({ reaction })
            });
            if (result.message) {
                replaceCurrentMessage(result.message);
                renderMessages();
            }
        } catch (error) {
            showToast(error.message);
        }
    }

    function replaceCurrentMessage(updated) {
        state.currentMessages = state.currentMessages.map((message) => message.id === updated.id ? updated : message);
    }

    function appendReplyQuote(bubble, message) {
        if (!message.replyTo) return;
        const quote = document.createElement("button");
        quote.type = "button";
        quote.className = "reply-quote";
        const author = document.createElement("strong");
        author.textContent = message.replySender || "Message";
        const body = document.createElement("span");
        body.textContent = message.replyDeleted ? "Deleted message" : message.replyText || "Message unavailable";
        quote.append(author, body);
        quote.addEventListener("click", (event) => {
            event.stopPropagation();
            document.getElementById(`message-${message.replyTo}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
        });
        bubble.append(quote);
    }

    function messageMeta(message, own) {
        const parts = [formatTime(new Date(message.createdAt))];
        if (message.edited) parts.push("edited");
        if (own && !message.deleted) parts.push((message.status || "SENT").toLowerCase());
        return parts.filter(Boolean).join(" - ");
    }

    /**
     * Відкриває меню дій над повідомленням: відповідь, редагування або видалення.
     */
    function openMessageActions(message) {
        state.selectedMessageAction = message;
        const own = message.sender === state.currentUser.username;
        $("replyMessageButton").classList.toggle("hidden", message.deleted);
        $("editMessageButton").classList.toggle("hidden", !own || message.deleted);
        $("deleteMessageButton").classList.toggle("hidden", !own || message.deleted);
        $("adminDeleteMessageButton").classList.toggle("hidden", !isAdmin() || own || message.deleted);
        messageActionsDialog.showModal();
    }

    function replyToMessage(message) {
        state.replyTo = message;
        $("replyAuthor").textContent = `Replying to ${message.sender}`;
        $("replyText").textContent = message.deleted ? "Deleted message" : message.text;
        $("replyPreview").classList.remove("hidden");
        messageActionsDialog.close();
        messageInput.focus();
    }

    function clearReply() {
        state.replyTo = null;
        $("replyPreview").classList.add("hidden");
    }

    async function editMessage(message) {
        messageActionsDialog.close();
        const text = window.prompt("Edit message", message.text);
        if (text === null || !text.trim()) return;
        try {
            await api(`/api/messages/${message.id}`, { method: "PUT", body: JSON.stringify({ text: text.trim() }) });
            await refreshChats();
        } catch (error) {
            showToast(error.message);
        }
    }

    async function deleteMessage(message) {
        messageActionsDialog.close();
        if (!window.confirm("Delete this message?")) return;
        try {
            await api(`/api/messages/${message.id}`, { method: "DELETE" });
            await refreshChats();
        } catch (error) {
            showToast(error.message);
        }
    }

    async function adminDeleteMessage(message) {
        messageActionsDialog.close();
        if (!window.confirm("Remove this message as administrator?")) return;
        try {
            await api(`/api/admin/messages/${message.id}`, { method: "DELETE" });
            await refreshChats();
        } catch (error) {
            showToast(error.message);
        }
    }

    /**
     * Відправляє текст повідомлення в поточний чат.
     */
    async function sendMessage(event) {
        event.preventDefault();
        const text = messageInput.value.trim();
        if (!text || !state.selected) return;
        try {
            const body = state.selected.type === "group" ? { group: state.selected.key, text } : { to: state.selected.key, text };
            if (state.replyTo) body.replyTo = state.replyTo.id;
            await api("/api/messages", { method: "POST", body: JSON.stringify(body) });
            messageInput.value = "";
            clearReply();
            autoResizeMessageInput();
            await refreshChats();
        } catch (error) {
            showToast(error.message);
        }
    }

    /**
     * Надсилає стан набору тексту не частіше заданого інтервалу, щоб не перевантажувати сервер.
     */
    async function maybeSendTyping() {
        if (!state.selected || !messageInput.value.trim()) return;
        const now = Date.now();
        if (now - state.lastTypingSentAt < 900) return;
        state.lastTypingSentAt = now;
        const body = state.selected.type === "group" ? { group: state.selected.key } : { to: state.selected.key };
        try {
            await api("/api/typing", { method: "POST", body: JSON.stringify(body) });
        } catch (error) {
            // Typing status is optional and should not interrupt typing.
        }
    }

    async function loadTyping(selected) {
        if (!selected) return;
        try {
            const query = selected.type === "group" ? { group: selected.key } : { with: selected.key };
            const result = await api(`/api/typing?${new URLSearchParams(query)}`);
            if (!sameChat(selected, state.selected)) return;
            typingIndicator.textContent = typingText(result.users || []);
        } catch (error) {
            typingIndicator.textContent = "";
        }
    }

    function typingText(users) {
        if (!users.length) return "";
        return users.length === 1 ? `${users[0]} is typing...` : `${users.join(", ")} are typing...`;
    }

    function autoResizeMessageInput() {
        messageInput.style.height = "auto";
        messageInput.style.height = `${Math.min(messageInput.scrollHeight, 120)}px`;
    }

    async function groupAction(action) {
        const group = $("groupNameInput").value.trim();
        if (!group) return;
        $("groupError").textContent = "";
        try {
            await api("/api/groups", { method: "POST", body: JSON.stringify({ action, group }) });
            groupDialog.close();
            $("groupNameInput").value = "";
            await refreshChats();
            const created = state.chats.find((chat) => chat.type === "group" && chat.key === group);
            if (created) await selectChat(created);
        } catch (error) {
            $("groupError").textContent = error.message;
        }
    }

    /**
     * Відкриває вікно керування групою і завантажує список учасників.
     */
    async function openGroupActions() {
        if (!state.selected || state.selected.type !== "group") return;
        $("membersDialogTitle").textContent = state.selected.title;
        $("membersError").textContent = "";
        $("memberNameInput").value = "";
        state.groupAvatarDataUrl = null;
        state.groupRemoveAvatar = false;
        $("groupAvatarInput").value = "";
        membersDialog.showModal();
        renderGroupAvatarEditor();
        await loadGroupMembers();
    }

    /**
     * Отримує учасників групи та права поточного користувача в цій групі.
     */
    async function loadGroupMembers() {
        if (!state.selected || state.selected.type !== "group") return;
        try {
            const group = encodeURIComponent(state.selected.key);
            const result = await api(`/api/groups/${group}/members`);
            state.selected.owner = Boolean(result.owner);
            state.selected.admin = Boolean(result.admin);
            state.selected.canManage = Boolean(result.canManage);
            $("groupOwnerLabel").textContent = result.owner
                    ? "You created this group."
                    : result.admin
                            ? "You are a group admin."
                            : "You are a group member.";
            $("memberManagement").classList.toggle("hidden", !result.canManage);
            $("groupAvatarManagement").classList.toggle("hidden", !result.canManage);
            $("deleteGroupButton").classList.toggle("hidden", !result.owner);
            $("leaveGroupButton").classList.toggle("hidden", result.owner);
            renderGroupAvatarEditor();
            renderGroupMembers(result.members || [], result);
        } catch (error) {
            $("membersError").textContent = error.message;
        }
    }

    /**
     * Виводить список учасників групи з кнопками керування ролями та видаленням.
     */
    function renderGroupMembers(members, permissions = {}) {
        const list = $("groupMembersList");
        list.replaceChildren();
        const owner = Boolean(permissions.owner);
        const canManage = Boolean(permissions.canManage);
        members.forEach((item) => {
            const member = typeof item === "string"
                    ? { username: item, owner: owner && item === state.currentUser.username, admin: false, online: false }
                    : item;
            const username = member.username;
            const row = document.createElement("div");
            row.className = "admin-user-row";
            const name = document.createElement("button");
            name.type = "button";
            name.className = "admin-user-name profile-name-button";
            name.textContent = username;
            name.addEventListener("click", () => { void openUserProfile(username); });
            const meta = document.createElement("div");
            meta.className = "admin-user-meta";
            const role = member.owner ? "group creator" : member.admin ? "group admin" : "member";
            meta.textContent = `${role} - ${member.online ? "online" : "offline"}`;
            const text = document.createElement("div");
            text.append(name, meta);
            row.append(text);
            const actions = document.createElement("div");
            actions.className = "admin-user-actions";
            if (owner && !member.owner && username !== state.currentUser.username) {
                const adminAction = document.createElement("button");
                adminAction.type = "button";
                adminAction.textContent = member.admin ? "Remove admin" : "Make admin";
                adminAction.classList.toggle("danger", member.admin);
                adminAction.addEventListener("click", () => { void setGroupAdmin(username, !member.admin); });
                actions.append(adminAction);
            }
            if (canManage && !member.owner && username !== state.currentUser.username && !(member.admin && !owner)) {
                const remove = document.createElement("button");
                remove.type = "button";
                remove.textContent = "Remove";
                remove.classList.add("danger");
                remove.addEventListener("click", () => { void removeGroupMember(username); });
                actions.append(remove);
            }
            if (actions.children.length) row.append(actions);
            list.append(row);
        });
    }

    /**
     * Показує блок редагування аватарки групи тільки користувачам з правом керування.
     */
    function renderGroupAvatarEditor() {
        if (!state.selected || state.selected.type !== "group") return;
        const preview = $("groupAvatarPreview");
        const placeholder = $("groupAvatarPlaceholder");
        const source = state.groupAvatarDataUrl || state.peerAvatarUrls.get(avatarCacheKey(state.selected));
        preview.classList.toggle("hidden", !source || state.groupRemoveAvatar);
        placeholder.classList.toggle("hidden", Boolean(source) && !state.groupRemoveAvatar);
        placeholder.textContent = "#";
        if (source && !state.groupRemoveAvatar) preview.src = source;
    }

    function readGroupAvatar(file) {
        if (!file) return;
        if (file.size > 1_000_000) {
            $("membersError").textContent = "Avatar must be smaller than 1 MB";
            $("groupAvatarInput").value = "";
            return;
        }
        const reader = new FileReader();
        reader.addEventListener("load", () => {
            state.groupAvatarDataUrl = String(reader.result);
            state.groupRemoveAvatar = false;
            $("membersError").textContent = "";
            renderGroupAvatarEditor();
        });
        reader.readAsDataURL(file);
    }

    /**
     * Зберігає нову аватарку групи або видаляє поточну.
     */
    async function saveGroupAvatar() {
        if (!state.selected || state.selected.type !== "group") return;
        $("membersError").textContent = "";
        try {
            const group = encodeURIComponent(state.selected.key);
            await api(`/api/groups/${group}/avatar`, {
                method: "PUT",
                body: JSON.stringify({
                    avatarDataUrl: state.groupAvatarDataUrl,
                    removeAvatar: state.groupRemoveAvatar
                })
            });
            state.groupAvatarDataUrl = null;
            $("groupAvatarInput").value = "";
            const key = avatarCacheKey(state.selected);
            const old = state.peerAvatarUrls.get(key);
            if (old) URL.revokeObjectURL(old);
            state.peerAvatarUrls.delete(key);
            state.peerAvatarUnavailable.delete(key);
            await refreshGroupState();
            showToast("Group avatar saved");
        } catch (error) {
            $("membersError").textContent = error.message;
        }
    }

    /**
     * Надсилає запит на видачу або зняття прав адміністратора групи.
     */
    async function setGroupAdmin(username, admin) {
        if (!state.selected || state.selected.type !== "group") return;
        $("membersError").textContent = "";
        try {
            const group = encodeURIComponent(state.selected.key);
            await api(`/api/groups/${group}/admins`, { method: admin ? "POST" : "DELETE", body: JSON.stringify({ username }) });
            await refreshGroupState();
            showToast(admin ? "Admin rights granted" : "Admin rights removed");
        } catch (error) {
            $("membersError").textContent = error.message;
        }
    }

    /**
     * Додає користувача до поточної групи за введеним іменем.
     */
    async function addGroupMember() {
        const username = $("memberNameInput").value.trim();
        if (!username || !state.selected || state.selected.type !== "group") return;
        $("membersError").textContent = "";
        try {
            const group = encodeURIComponent(state.selected.key);
            await api(`/api/groups/${group}/members`, { method: "POST", body: JSON.stringify({ username }) });
            $("memberNameInput").value = "";
            await refreshGroupState();
            showToast("Member added");
        } catch (error) {
            $("membersError").textContent = error.message;
        }
    }

    /**
     * Видаляє учасника з групи після підтвердження дії.
     */
    async function removeGroupMember(username) {
        if (!state.selected || state.selected.type !== "group") return;
        if (!window.confirm(`Remove ${username} from ${state.selected.title}?`)) return;
        $("membersError").textContent = "";
        try {
            const group = encodeURIComponent(state.selected.key);
            await api(`/api/groups/${group}/members`, { method: "DELETE", body: JSON.stringify({ username }) });
            $("memberNameInput").value = "";
            await refreshGroupState();
            showToast("Member removed");
        } catch (error) {
            $("membersError").textContent = error.message;
        }
    }

    async function leaveGroup() {
        if (!state.selected || state.selected.type !== "group") return;
        if (!window.confirm(`Leave ${state.selected.title}?`)) return;
        try {
            await api(`/api/groups/${encodeURIComponent(state.selected.key)}/membership`, { method: "DELETE" });
            membersDialog.close();
            await refreshChats();
            showToast("You left the group");
        } catch (error) {
            $("membersError").textContent = error.message;
        }
    }

    async function deletePrivateChat() {
        if (!state.selected || state.selected.type !== "private") return;
        const username = state.selected.key;
        if (!window.confirm(`Delete the chat with ${username} and all its messages?`)) return;
        try {
            await api(`/api/friends/${encodeURIComponent(username)}`, { method: "DELETE" });
            chatActionsDialog.close();
            await refreshChats();
            showToast("Chat deleted");
        } catch (error) {
            showToast(error.message);
        }
    }

    /**
     * Відкриває контекстне меню поточного чату.
     */
    function openChatActions() {
        const chat = state.selected;
        if (!chat) return;
        $("manageGroupButton").classList.toggle("hidden", chat.type !== "group");
        $("deletePrivateChatButton").classList.toggle("hidden", chat.type !== "private");
        chatActionsDialog.showModal();
        messageSearch.focus();
    }

    async function deleteGroup() {
        if (!state.selected || state.selected.type !== "group") return;
        if (!window.confirm(`Delete ${state.selected.title} for every member?`)) return;
        try {
            await api(`/api/groups/${encodeURIComponent(state.selected.key)}`, { method: "DELETE" });
            membersDialog.close();
            await refreshChats();
            showToast("Group deleted");
        } catch (error) {
            $("membersError").textContent = error.message;
        }
    }

    /**
     * Завантажує і показує профіль іншого користувача.
     */
    async function openUserProfile(username) {
        if (!username) return;
        $("viewProfileError").textContent = "";
        const openChat = $("openProfileChatButton");
        openChat.classList.add("hidden");
        openChat.onclick = null;
        if (state.viewProfileAvatarUrl) URL.revokeObjectURL(state.viewProfileAvatarUrl);
        state.viewProfileAvatarUrl = null;
        renderViewedProfile({ username, online: false, description: "", hasAvatar: false, role: "USER" });
        if (!userProfileDialog.open) userProfileDialog.showModal();
        try {
            const result = await api(`/api/users/${encodeURIComponent(username)}/profile`);
            const user = result.user;
            renderViewedProfile(user);
            if (user.hasAvatar) {
                await loadViewedProfileAvatar(user);
            }
            const chat = state.chats.find((item) => item.type === "private" && item.key === user.username);
            openChat.classList.toggle("hidden", !chat);
            openChat.onclick = chat ? () => {
                userProfileDialog.close();
                void selectChat(chat);
            } : null;
        } catch (error) {
            $("viewProfileError").textContent = error.message;
        }
    }

    function renderViewedProfile(user) {
        $("viewProfileUsername").textContent = user.username || "User";
        $("viewProfileStatus").textContent = `${user.role || "USER"} - ${user.online ? "online" : "offline"}`;
        $("viewProfileDescription").textContent = user.description || "No profile description.";
        const avatar = $("viewProfileAvatar");
        avatar.replaceChildren();
        if (state.viewProfileAvatarUrl) {
            const image = document.createElement("img");
            image.src = state.viewProfileAvatarUrl;
            image.alt = "";
            avatar.append(image);
        } else {
            avatar.textContent = profileInitial(user.username);
        }
    }

    async function loadViewedProfileAvatar(user) {
        try {
            const response = await fetch(apiUrl(`/api/users/${encodeURIComponent(user.username)}/avatar`), {
                headers: { Authorization: `Bearer ${state.token}`, "ngrok-skip-browser-warning": "true" }
            });
            if (!response.ok) throw new Error("Avatar unavailable");
            const image = await response.blob();
            if (state.viewProfileAvatarUrl) URL.revokeObjectURL(state.viewProfileAvatarUrl);
            state.viewProfileAvatarUrl = URL.createObjectURL(image);
            renderViewedProfile(user);
        } catch (error) {
            if (state.viewProfileAvatarUrl) URL.revokeObjectURL(state.viewProfileAvatarUrl);
            state.viewProfileAvatarUrl = null;
            renderViewedProfile(user);
        }
    }

    function openFriendsPanel() {
        $("friendsError").textContent = "";
        $("friendSearch").value = "";
        state.friendSearchResults = [];
        renderFriendUsers();
        friendsDialog.showModal();
    }

    /**
     * Відкриває діалог редагування власного профілю.
     */
    async function openProfile() {
        const user = state.currentUser;
        if (!user) return;
        $("profileError").textContent = "";
        $("profileDescriptionInput").value = user.description || "";
        $("quickReactionSelect").value = quickReaction();
        $("profileAvatarInput").value = "";
        state.profileAvatarDataUrl = null;
        state.profileRemoveAvatar = false;
        state.groupAvatarDataUrl = null;
        state.groupRemoveAvatar = false;
        renderProfileEditor();
        profileDialog.showModal();
    }

    function renderProfileEditor() {
        const image = $("profileAvatarPreview");
        const placeholder = $("profileAvatarPlaceholder");
        const source = state.profileAvatarDataUrl || state.avatarObjectUrl;
        image.classList.toggle("hidden", !source || state.profileRemoveAvatar);
        placeholder.classList.toggle("hidden", Boolean(source) && !state.profileRemoveAvatar);
        placeholder.textContent = profileInitial(state.currentUser?.username);
        if (source && !state.profileRemoveAvatar) image.src = source;
    }

    function readProfileAvatar(file) {
        if (!file) return;
        if (file.size > 1_000_000) {
            $("profileError").textContent = "Avatar must be smaller than 1 MB";
            $("profileAvatarInput").value = "";
            return;
        }
        const reader = new FileReader();
        reader.addEventListener("load", () => {
            state.profileAvatarDataUrl = String(reader.result);
            state.profileRemoveAvatar = false;
            $("profileError").textContent = "";
            renderProfileEditor();
        });
        reader.readAsDataURL(file);
    }

    /**
     * Зберігає опис, аватарку та швидку реакцію профілю.
     */
    async function saveProfile(event) {
        event.preventDefault();
        $("profileError").textContent = "";
        try {
            const updated = await api("/api/me", {
                method: "PUT",
                body: JSON.stringify({
                    description: $("profileDescriptionInput").value,
                    avatarDataUrl: state.profileAvatarDataUrl,
                    removeAvatar: state.profileRemoveAvatar,
                    quickReaction: $("quickReactionSelect").value
                })
            });
            state.currentUser = updated;
            if (state.profileAvatarDataUrl) {
                await loadProfileAvatar();
            } else if (state.profileRemoveAvatar) {
                if (state.avatarObjectUrl) URL.revokeObjectURL(state.avatarObjectUrl);
                state.avatarObjectUrl = null;
            }
            renderProfileSummary();
            profileDialog.close();
            showToast("Profile saved");
        } catch (error) {
            $("profileError").textContent = error.message;
        }
    }

    /**
     * Показує список користувачів для керування списком друзів.
     */
    function renderFriendUsers() {
        const list = $("friendUserList");
        const filter = $("friendSearch").value.trim().toLowerCase();
        const friendNames = new Set(state.friends);
        const users = state.friendSearchResults;
        list.replaceChildren();
        users.forEach((user) => {
            const row = document.createElement("div");
            row.className = "admin-user-row";
            const text = document.createElement("div");
            const name = document.createElement("div");
            name.className = "admin-user-name";
            name.textContent = user.username;
            const meta = document.createElement("div");
            meta.className = "admin-user-meta";
            meta.textContent = user.online ? "online" : "offline";
            text.append(name, meta);
            const actions = document.createElement("div");
            actions.className = "admin-user-actions";
            const viewAction = document.createElement("button");
            viewAction.type = "button";
            viewAction.textContent = "View";
            viewAction.addEventListener("click", () => { void openUserProfile(user.username); });
            const action = document.createElement("button");
            const isFriend = friendNames.has(user.username);
            action.type = "button";
            action.textContent = isFriend ? "Remove" : "Add friend";
            action.classList.toggle("danger", isFriend);
            action.addEventListener("click", () => friendAction(user, isFriend ? "DELETE" : "POST"));
            actions.append(viewAction, action);
            row.append(text, actions);
            list.append(row);
        });
        if (!users.length) {
            const empty = document.createElement("p");
            empty.className = "empty-state";
            empty.textContent = filter ? "No users found." : "Enter a nickname to search.";
            list.append(empty);
        }
    }

    async function searchFriendUsers() {
        const term = $("friendSearch").value.trim();
        const version = ++state.friendSearchVersion;
        $("friendsError").textContent = "";
        if (!term) {
            state.friendSearchResults = [];
            renderFriendUsers();
            return;
        }
        try {
            const result = await api(`/api/users/search?${new URLSearchParams({ query: term })}`);
            if (version !== state.friendSearchVersion) return;
            state.friendSearchResults = result.users || [];
            renderFriendUsers();
        } catch (error) {
            if (version !== state.friendSearchVersion) return;
            state.friendSearchResults = [];
            renderFriendUsers();
            $("friendsError").textContent = error.message;
        }
    }

    async function friendAction(user, method) {
        $("friendsError").textContent = "";
        try {
            await api(`/api/friends/${encodeURIComponent(user.username)}`, { method });
            await refreshChats();
            await searchFriendUsers();
            if (method === "POST") {
                const chat = state.chats.find((item) => item.type === "private" && item.key === user.username);
                if (chat) await selectChat(chat);
            }
            showToast(method === "POST" ? "Friend added" : "Friend removed");
        } catch (error) {
            $("friendsError").textContent = error.message;
        }
    }

    /**
     * Відкриває адміністративну панель для користувача з роллю ADMIN.
     */
    function openAdminPanel() {
        if (!isAdmin()) return;
        $("adminError").textContent = "";
        $("adminUserSearch").value = "";
        renderAdminUsers();
        adminDialog.showModal();
    }

    /**
     * Виводить користувачів у панелі адміністратора.
     */
    function renderAdminUsers() {
        const list = $("adminUserList");
        const filter = $("adminUserSearch").value.trim().toLowerCase();
        list.replaceChildren();
        const users = state.users.filter((user) => user.username.toLowerCase().includes(filter));
        users.forEach((user) => list.append(adminUserRow(user)));
        if (!users.length) {
            const empty = document.createElement("p");
            empty.className = "empty-state";
            empty.textContent = "No users found.";
            list.append(empty);
        }
    }

    function adminUserRow(user) {
        const row = document.createElement("div");
        row.className = "admin-user-row";
        const text = document.createElement("div");
        const name = document.createElement("div");
        name.className = "admin-user-name";
        name.textContent = user.username;
        const meta = document.createElement("div");
        meta.className = "admin-user-meta";
        meta.textContent = `${user.role} - ${user.blocked ? "blocked" : user.online ? "online" : "offline"}`;
        text.append(name, meta);
        const actions = document.createElement("div");
        actions.className = "admin-user-actions";
        const viewAction = document.createElement("button");
        viewAction.type = "button";
        viewAction.textContent = "View";
        viewAction.addEventListener("click", () => { void openUserProfile(user.username); });
        const blockAction = document.createElement("button");
        blockAction.type = "button";
        blockAction.textContent = user.blocked ? "Unblock" : "Block";
        blockAction.classList.toggle("danger", !user.blocked);
        blockAction.disabled = user.username === state.currentUser.username;
        blockAction.addEventListener("click", () => adminUserAction(user, user.blocked ? "unblock" : "block"));
        const deleteAction = document.createElement("button");
        deleteAction.type = "button";
        deleteAction.textContent = "Delete";
        deleteAction.classList.add("danger");
        deleteAction.disabled = user.username === state.currentUser.username;
        deleteAction.addEventListener("click", () => deleteUser(user));
        actions.append(viewAction, blockAction, deleteAction);
        row.append(text, actions);
        return row;
    }

    async function adminUserAction(user, action) {
        $("adminError").textContent = "";
        try {
            await api(`/api/admin/users/${encodeURIComponent(user.username)}/${action}`, { method: "POST" });
            await refreshChats();
            renderAdminUsers();
            showToast(action === "block" ? "User blocked" : "User unblocked");
        } catch (error) {
            $("adminError").textContent = error.message;
        }
    }

    async function deleteUser(user) {
        if (!window.confirm(`Permanently delete ${user.username} and all related data?`)) return;
        $("adminError").textContent = "";
        try {
            await api(`/api/admin/users/${encodeURIComponent(user.username)}`, { method: "DELETE" });
            await refreshChats();
            renderAdminUsers();
            showToast("User permanently deleted");
        } catch (error) {
            $("adminError").textContent = error.message;
        }
    }


    /**
     * Налаштовує перетягування межі між списком чатів і основною областю розмови.
     */
    function setupSidebarResize() {
        const layout = $("chatLayout");
        const handle = $("sidebarResizeHandle");
        if (!layout || !handle) return;
        const stored = Number(localStorage.getItem(SIDEBAR_WIDTH_KEY));
        if (Number.isFinite(stored) && stored > 0) setSidebarWidth(stored);

        let dragging = false;
        const startResize = (event) => {
            dragging = true;
            document.body.classList.add("resizing-sidebar");
            handle.setPointerCapture?.(event.pointerId);
            event.preventDefault();
        };
        const moveResize = (event) => {
            if (!dragging) return;
            const rect = layout.getBoundingClientRect();
            setSidebarWidth(event.clientX - rect.left);
        };
        const stopResize = () => {
            if (!dragging) return;
            dragging = false;
            document.body.classList.remove("resizing-sidebar");
        };
        handle.addEventListener("pointerdown", startResize);
        handle.addEventListener("pointermove", moveResize);
        handle.addEventListener("pointerup", stopResize);
        handle.addEventListener("pointercancel", stopResize);
        handle.addEventListener("dblclick", () => {
            localStorage.removeItem(SIDEBAR_WIDTH_KEY);
            layout.style.removeProperty("--sidebar-width");
        });
        handle.addEventListener("keydown", (event) => {
            if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
            event.preventDefault();
            const current = Number.parseFloat(getComputedStyle(layout).getPropertyValue("--sidebar-width")) || 330;
            if (event.key === "Home") {
                setSidebarWidth(180);
            } else if (event.key === "End") {
                setSidebarWidth(window.innerWidth - 260);
            } else {
                setSidebarWidth(current + (event.key === "ArrowRight" ? 24 : -24));
            }
        });
        window.addEventListener("resize", () => {
            const current = Number.parseFloat(getComputedStyle(layout).getPropertyValue("--sidebar-width"));
            if (Number.isFinite(current)) setSidebarWidth(current);
        });
    }

    /**
     * Застосовує ширину лівої панелі з допустимими мінімальним і максимальним значеннями.
     */
    function setSidebarWidth(width) {
        const layout = $("chatLayout");
        if (!layout) return;
        const min = window.innerWidth <= 720 ? 135 : 180;
        const max = Math.max(min, window.innerWidth - (window.innerWidth <= 720 ? 170 : 320));
        const next = Math.max(min, Math.min(max, Math.round(width)));
        layout.style.setProperty("--sidebar-width", `${next}px`);
        localStorage.setItem(SIDEBAR_WIDTH_KEY, String(next));
    }

    /**
     * Заповнює випадаючий список швидкої реакції в профілі.
     */
    function populateQuickReactionSelect() {
        const select = $("quickReactionSelect");
        select.replaceChildren();
        REACTION_OPTIONS.forEach((reaction) => {
            const option = document.createElement("option");
            option.value = reaction;
            option.textContent = reaction;
            select.append(option);
        });
    }

    function senderColor(sender) {
        let hash = 0;
        for (const character of String(sender || "")) hash = ((hash << 5) - hash) + character.charCodeAt(0);
        return SENDER_COLORS[Math.abs(hash) % SENDER_COLORS.length];
    }

    function formatTime(date) {
        return Number.isNaN(date.getTime()) ? "" : date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    }

    authForm.addEventListener("submit", authenticate);
    authModeButton.addEventListener("click", () => setAuthMode(!registrationMode));
    $("logoutButton").addEventListener("click", logout);
    $("themeButton").addEventListener("click", () => setTheme(state.theme === "light" ? "dark" : "light"));
    $("friendsButton").addEventListener("click", openFriendsPanel);
    $("profileButton").addEventListener("click", () => { void openProfile(); });
    $("chatSearch").addEventListener("input", renderChats);
    messageSearch.addEventListener("input", renderMessages);
    $("clearMessageSearchButton").addEventListener("click", () => {
        messageSearch.value = "";
        renderMessages();
        messageSearch.focus();
    });
    $("composerForm").addEventListener("submit", sendMessage);
    messageInput.addEventListener("input", () => { autoResizeMessageInput(); void maybeSendTyping(); });
    messageInput.addEventListener("keydown", (event) => {
        if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            $("composerForm").requestSubmit();
        }
    });
    $("newGroupButton").addEventListener("click", () => { $("groupError").textContent = ""; groupDialog.showModal(); $("groupNameInput").focus(); });
    $("createGroupButton").addEventListener("click", () => groupAction("create"));
    $("joinGroupButton").addEventListener("click", () => groupAction("join"));
    $("conversationIdentity").addEventListener("click", () => {
        if (state.selected?.type === "private") void openUserProfile(state.selected.key);
    });
    chatActionsButton.addEventListener("click", openChatActions);
    $("manageGroupButton").addEventListener("click", () => {
        chatActionsDialog.close();
        void openGroupActions();
    });
    $("addMemberButton").addEventListener("click", () => { void addGroupMember(); });
    $("leaveGroupButton").addEventListener("click", () => { void leaveGroup(); });
    $("deleteGroupButton").addEventListener("click", () => { void deleteGroup(); });
    $("deletePrivateChatButton").addEventListener("click", () => { void deletePrivateChat(); });
    $("replyMessageButton").addEventListener("click", () => {
        if (state.selectedMessageAction) replyToMessage(state.selectedMessageAction);
    });
    $("editMessageButton").addEventListener("click", () => {
        if (state.selectedMessageAction) void editMessage(state.selectedMessageAction);
    });
    $("deleteMessageButton").addEventListener("click", () => {
        if (state.selectedMessageAction) void deleteMessage(state.selectedMessageAction);
    });
    $("adminDeleteMessageButton").addEventListener("click", () => {
        if (state.selectedMessageAction) void adminDeleteMessage(state.selectedMessageAction);
    });
    $("cancelReplyButton").addEventListener("click", clearReply);
    $("groupAvatarInput").addEventListener("change", (event) => readGroupAvatar(event.target.files?.[0]));
    $("removeGroupAvatarButton").addEventListener("click", () => {
        state.groupAvatarDataUrl = null;
        state.groupRemoveAvatar = true;
        $("groupAvatarInput").value = "";
        renderGroupAvatarEditor();
    });
    $("saveGroupAvatarButton").addEventListener("click", () => { void saveGroupAvatar(); });
    $("profileAvatarInput").addEventListener("change", (event) => readProfileAvatar(event.target.files?.[0]));
    $("removeAvatarButton").addEventListener("click", () => {
        state.profileAvatarDataUrl = null;
        state.profileRemoveAvatar = true;
        $("profileAvatarInput").value = "";
        renderProfileEditor();
    });
    $("profileForm").addEventListener("submit", (event) => { void saveProfile(event); });
    $("friendSearch").addEventListener("input", () => { void searchFriendUsers(); });
    $("adminButton").addEventListener("click", openAdminPanel);
    $("adminUserSearch").addEventListener("input", renderAdminUsers);
    // Нижче підключаються всі DOM-обробники: кнопки, форми, пошук, введення повідомлень і закриття діалогів.
    document.querySelectorAll("[data-close]").forEach((button) => button.addEventListener("click", () => $(button.dataset.close).close()));
    document.addEventListener("click", (event) => {
        const menu = $("reactionMenu");
        if (!menu.classList.contains("hidden") && !menu.contains(event.target)) {
            closeReactionMenu();
        }
    });
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape") closeReactionMenu();
    });
    messageList.addEventListener("scroll", closeReactionMenu);

    setupSidebarResize();
    populateQuickReactionSelect();
    setTheme(state.theme);
    if (state.token) openApp().catch(logout);
})();
