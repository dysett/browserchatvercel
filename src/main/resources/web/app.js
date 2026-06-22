(() => {
    const SESSION_TOKEN_KEY = "onlineChatToken";
    localStorage.removeItem(SESSION_TOKEN_KEY);
    const API_BASE_URL = String(window.CHAT_API_URL || "").replace(/\/+$/, "");

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
        eventController: null,
        eventReconnectTimer: null,
        refreshTimer: null,
        lastTypingSentAt: 0,
        theme: localStorage.getItem("onlineChatTheme") || "light"
    };

    const SENDER_COLORS = ["#087fca", "#0c9a8e", "#4777d1", "#b06818", "#9a4eb0", "#be4a67"];
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
    const groupActionsButton = $("groupActionsButton");
    const privateChatActionsButton = $("privateChatActionsButton");
    const groupDialog = $("groupDialog");
    const membersDialog = $("membersDialog");
    const friendsDialog = $("friendsDialog");
    const adminDialog = $("adminDialog");
    let registrationMode = false;

    function setTheme(theme) {
        state.theme = theme;
        document.documentElement.dataset.theme = theme;
        localStorage.setItem("onlineChatTheme", theme);
        $("themeButton").textContent = theme === "light" ? "Dark" : "Light";
    }

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

    function setAuthMode(register) {
        registrationMode = register;
        authTitle.textContent = register ? "Create account" : "Online Chat";
        authDescription.textContent = register ? "Choose a username and password to start chatting." : "Sign in to continue your conversations.";
        authSubmit.textContent = register ? "Create account" : "Sign in";
        authModeButton.textContent = register ? "I already have an account" : "Create an account";
        passwordInput.autocomplete = register ? "new-password" : "current-password";
        authError.textContent = "";
    }

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

    async function openApp() {
        state.currentUser = await api("/api/me");
        $("accountLabel").textContent = `${state.currentUser.username} - ${state.currentUser.role}`;
        $("adminButton").classList.toggle("hidden", !isAdmin());
        authScreen.classList.add("hidden");
        appScreen.classList.remove("hidden");
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
        $("adminButton").classList.add("hidden");
    }

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

    function handleServerEvent(event) {
        if (event.command === "EVENT_TYPING") {
            void loadTyping(state.selected);
            return;
        }
        if (event.command) scheduleRefresh();
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

    async function refreshChats() {
        const result = await api("/api/chats");
        state.currentUser = result.currentUser;
        state.users = result.users || [];
        state.friends = result.friends || [];
        state.chats = result.chats || [];
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

    function sameChat(first, second) {
        return first && second && first.type === second.type && first.key === second.key;
    }

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
        addSection("PEOPLE", "private");
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
        const text = document.createElement("span");
        const title = document.createElement("div");
        title.className = "chat-title";
        title.textContent = chat.title;
        const preview = document.createElement("div");
        preview.className = "chat-kind";
        preview.textContent = chatPreview(chat);
        text.append(title, preview);
        button.append(dot, text);
        if (chat.unreadCount > 0) {
            const unread = document.createElement("span");
            unread.className = "unread-badge";
            unread.textContent = chat.unreadCount > 99 ? "99+" : String(chat.unreadCount);
            button.append(unread);
        }
        button.addEventListener("click", () => selectChat(chat));
        return button;
    }

    function chatPreview(chat) {
        if (!chat.lastText) return chat.type === "group" ? "Group" : chat.online ? "Online" : "Offline";
        const prefix = chat.type === "group" && chat.lastSender ? `${chat.lastSender}: ` : "";
        return `${formatTime(new Date(chat.lastCreatedAt))} ${prefix}${chat.lastText}`.trim();
    }

    async function selectChat(chat) {
        state.selected = chat;
        renderChats();
        updateConversationHeader();
        await loadMessages();
        messageInput.focus();
    }

    function updateConversationHeader() {
        const chat = state.selected;
        const enabled = Boolean(chat);
        messageInput.disabled = !enabled;
        messageSearch.disabled = !enabled;
        sendButton.disabled = !enabled;
        groupActionsButton.classList.toggle("hidden", !chat || chat.type !== "group");
        privateChatActionsButton.classList.toggle("hidden", !chat || chat.type !== "private");
        if (!chat) {
            conversationTitle.textContent = "Choose a chat";
            conversationStatus.textContent = "Select a user or group on the left.";
            return;
        }
        conversationTitle.textContent = chat.title;
        conversationStatus.textContent = chat.type === "group" ? "Group chat" : chat.online ? "Online" : "Offline";
    }

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

    function renderMessages() {
        const shouldStick = messageList.scrollTop + messageList.clientHeight >= messageList.scrollHeight - 30;
        const filter = messageSearch.value.trim().toLowerCase();
        const messages = state.currentMessages.filter((message) => {
            return !filter || `${message.sender} ${message.text}`.toLowerCase().includes(filter);
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

    function messageNode(message) {
        const own = message.sender === state.currentUser.username;
        const row = document.createElement("article");
        row.className = `message-row${own ? " own" : ""}`;
        const bubble = document.createElement("div");
        bubble.className = "message-bubble";
        bubble.style.setProperty("--sender-color", senderColor(message.sender));
        if (state.selected.type === "group" && !own) {
            const author = document.createElement("div");
            author.className = "message-author";
            author.textContent = message.sender;
            bubble.append(author);
        }
        const text = document.createElement("div");
        text.className = `message-text${message.edited ? " message-edited" : ""}`;
        text.textContent = message.text;
        const meta = document.createElement("div");
        meta.className = "message-meta";
        meta.textContent = messageMeta(message, own);
        bubble.append(text, meta);
        appendMessageActions(bubble, row, message, own);
        row.append(bubble);
        return row;
    }

    function messageMeta(message, own) {
        const parts = [formatTime(new Date(message.createdAt))];
        if (message.edited) parts.push("edited");
        if (own && !message.deleted) parts.push((message.status || "SENT").toLowerCase());
        return parts.filter(Boolean).join(" - ");
    }

    function appendMessageActions(bubble, row, message, own) {
        if (message.deleted) return;
        const actions = document.createElement("div");
        actions.className = "message-actions";
        if (own) {
            actions.append(actionButton("Edit", () => editMessage(message)), actionButton("Delete", () => deleteMessage(message)));
            row.addEventListener("contextmenu", (event) => {
                event.preventDefault();
                ownMessageMenu(message);
            });
        } else if (isAdmin()) {
            actions.append(actionButton("Remove", () => adminDeleteMessage(message)));
            row.addEventListener("contextmenu", (event) => {
                event.preventDefault();
                adminDeleteMessage(message);
            });
        }
        if (actions.children.length) bubble.append(actions);
    }

    function actionButton(label, action) {
        const button = document.createElement("button");
        button.type = "button";
        button.textContent = label;
        button.addEventListener("click", action);
        return button;
    }

    function ownMessageMenu(message) {
        const action = window.prompt("Type edit or delete.");
        if (action && action.toLowerCase() === "edit") editMessage(message);
        if (action && action.toLowerCase() === "delete") deleteMessage(message);
    }

    async function editMessage(message) {
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
        if (!window.confirm("Delete this message?")) return;
        try {
            await api(`/api/messages/${message.id}`, { method: "DELETE" });
            await refreshChats();
        } catch (error) {
            showToast(error.message);
        }
    }

    async function adminDeleteMessage(message) {
        if (!window.confirm("Remove this message as administrator?")) return;
        try {
            await api(`/api/admin/messages/${message.id}`, { method: "DELETE" });
            await refreshChats();
        } catch (error) {
            showToast(error.message);
        }
    }

    async function sendMessage(event) {
        event.preventDefault();
        const text = messageInput.value.trim();
        if (!text || !state.selected) return;
        try {
            const body = state.selected.type === "group" ? { group: state.selected.key, text } : { to: state.selected.key, text };
            await api("/api/messages", { method: "POST", body: JSON.stringify(body) });
            messageInput.value = "";
            autoResizeMessageInput();
            await refreshChats();
        } catch (error) {
            showToast(error.message);
        }
    }

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

    async function openGroupActions() {
        if (!state.selected || state.selected.type !== "group") return;
        $("membersDialogTitle").textContent = state.selected.title;
        $("membersError").textContent = "";
        $("memberNameInput").value = "";
        membersDialog.showModal();
        await loadGroupMembers();
    }

    async function loadGroupMembers() {
        if (!state.selected || state.selected.type !== "group") return;
        try {
            const group = encodeURIComponent(state.selected.key);
            const result = await api(`/api/groups/${group}/members`);
            state.selected.owner = Boolean(result.owner);
            $("groupOwnerLabel").textContent = result.owner ? "You created this group." : "You are a group member.";
            $("memberManagement").classList.toggle("hidden", !result.owner);
            $("deleteGroupButton").classList.toggle("hidden", !result.owner);
            $("leaveGroupButton").classList.toggle("hidden", result.owner);
            renderGroupMembers(result.members || [], result.owner);
        } catch (error) {
            $("membersError").textContent = error.message;
        }
    }

    function renderGroupMembers(members, owner) {
        const list = $("groupMembersList");
        list.replaceChildren();
        members.forEach((username) => {
            const row = document.createElement("div");
            row.className = "admin-user-row";
            const name = document.createElement("div");
            name.className = "admin-user-name";
            name.textContent = username;
            const meta = document.createElement("div");
            meta.className = "admin-user-meta";
            meta.textContent = owner && username === state.currentUser.username ? "group creator" : "member";
            const text = document.createElement("div");
            text.append(name, meta);
            row.append(text);
            if (owner && username !== state.currentUser.username) {
                const remove = document.createElement("button");
                remove.type = "button";
                remove.textContent = "Remove";
                remove.classList.add("danger");
                remove.addEventListener("click", () => { void removeGroupMember(username); });
                row.append(remove);
            }
            list.append(row);
        });
    }

    async function memberAction(method) {
        const username = $("memberNameInput").value.trim();
        if (!username || !state.selected || state.selected.type !== "group") return;
        if (method === "DELETE") {
            await removeGroupMember(username);
            return;
        }
        $("membersError").textContent = "";
        try {
            const group = encodeURIComponent(state.selected.key);
            await api(`/api/groups/${group}/members`, { method, body: JSON.stringify({ username }) });
            $("memberNameInput").value = "";
            await loadGroupMembers();
            await refreshChats();
            showToast(method === "POST" ? "Member added" : "Member removed");
        } catch (error) {
            $("membersError").textContent = error.message;
        }
    }

    async function removeGroupMember(username) {
        if (!state.selected || state.selected.type !== "group") return;
        if (!window.confirm(`Remove ${username} from ${state.selected.title}?`)) return;
        $("membersError").textContent = "";
        try {
            const group = encodeURIComponent(state.selected.key);
            await api(`/api/groups/${group}/members`, { method: "DELETE", body: JSON.stringify({ username }) });
            $("memberNameInput").value = "";
            await loadGroupMembers();
            await refreshChats();
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
            await refreshChats();
            showToast("Chat deleted");
        } catch (error) {
            showToast(error.message);
        }
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

    function openFriendsPanel() {
        $("friendsError").textContent = "";
        $("friendSearch").value = "";
        state.friendSearchResults = [];
        renderFriendUsers();
        friendsDialog.showModal();
    }

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
            const action = document.createElement("button");
            const isFriend = friendNames.has(user.username);
            action.type = "button";
            action.textContent = isFriend ? "Remove" : "Add friend";
            action.classList.toggle("danger", isFriend);
            action.addEventListener("click", () => friendAction(user, isFriend ? "DELETE" : "POST"));
            row.append(text, action);
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

    function openAdminPanel() {
        if (!isAdmin()) return;
        $("adminError").textContent = "";
        $("adminUserSearch").value = "";
        renderAdminUsers();
        adminDialog.showModal();
    }

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
        actions.append(blockAction, deleteAction);
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
    $("chatSearch").addEventListener("input", renderChats);
    messageSearch.addEventListener("input", renderMessages);
    $("composerForm").addEventListener("submit", sendMessage);
    messageInput.addEventListener("input", () => { autoResizeMessageInput(); void maybeSendTyping(); });
    $("newGroupButton").addEventListener("click", () => { $("groupError").textContent = ""; groupDialog.showModal(); $("groupNameInput").focus(); });
    $("createGroupButton").addEventListener("click", () => groupAction("create"));
    $("joinGroupButton").addEventListener("click", () => groupAction("join"));
    groupActionsButton.addEventListener("click", () => { void openGroupActions(); });
    $("addMemberButton").addEventListener("click", () => memberAction("POST"));
    $("removeMemberButton").addEventListener("click", () => memberAction("DELETE"));
    $("leaveGroupButton").addEventListener("click", () => { void leaveGroup(); });
    $("deleteGroupButton").addEventListener("click", () => { void deleteGroup(); });
    privateChatActionsButton.addEventListener("click", () => { void deletePrivateChat(); });
    $("friendSearch").addEventListener("input", () => { void searchFriendUsers(); });
    $("adminButton").addEventListener("click", openAdminPanel);
    $("adminUserSearch").addEventListener("input", renderAdminUsers);
    document.querySelectorAll("[data-close]").forEach((button) => button.addEventListener("click", () => $(button.dataset.close).close()));

    setTheme(state.theme);
    if (state.token) openApp().catch(logout);
})();
