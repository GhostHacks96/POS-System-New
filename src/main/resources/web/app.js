"use strict";

const state = {
    apiBaseUrl: "",
    token: sessionStorage.getItem("ghostpos-token") || "",
    user: null,
    permissions: new Set(),
    storeName: "GhostPOS",
    currentView: "overview",
    toastTimer: null
};

const loginScreen = document.getElementById("login-screen");
const application = document.getElementById("application");
const viewRoot = document.getElementById("view-root");
const modal = document.getElementById("modal");
const modalContent = document.getElementById("modal-content");

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, character => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;"
    })[character]);
}

function money(value) {
    return new Intl.NumberFormat(undefined, { style: "currency", currency: "USD" })
        .format(Number(value || 0));
}

function dateTime(value) {
    if (!value) return "—";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString();
}

function hasPermission(permission) {
    return state.permissions.has(permission);
}

function setNotice(message, isError = false) {
    const toast = document.getElementById("toast");
    toast.textContent = message;
    toast.classList.toggle("error", isError);
    toast.classList.add("visible");
    window.clearTimeout(state.toastTimer);
    state.toastTimer = window.setTimeout(() => toast.classList.remove("visible"), 3800);
}

async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set("Accept", "application/json");
    if (options.body !== undefined) headers.set("Content-Type", "application/json");
    if (state.token) headers.set("Authorization", `Bearer ${state.token}`);

    let response;
    try {
        response = await fetch(`${state.apiBaseUrl}${path}`, {
            ...options,
            headers,
            body: options.body === undefined ? undefined : JSON.stringify(options.body)
        });
    } catch {
        throw new Error("Cannot reach the GhostPOS API. Check the server and web.api-base-url setting.");
    }

    const contentType = response.headers.get("content-type") || "";
    const payload = contentType.includes("application/json")
        ? await response.json()
        : await response.text();
    if (!response.ok) {
        if (response.status === 401 && state.token) {
            clearSession();
            showLogin("Your session expired. Sign in again.");
        }
        const message = payload && typeof payload === "object" && payload.message
            ? payload.message
            : `Request failed (${response.status})`;
        throw new Error(message);
    }
    return payload;
}

function clearSession() {
    state.token = "";
    state.user = null;
    state.permissions.clear();
    sessionStorage.removeItem("ghostpos-token");
}

function showLogin(message = "") {
    application.hidden = true;
    loginScreen.hidden = false;
    document.getElementById("login-error").textContent = message;
    document.getElementById("login-password").value = "";
}

function showApplication(user, permissions) {
    state.user = user;
    state.permissions = new Set(permissions || []);
    loginScreen.hidden = true;
    application.hidden = false;
    document.getElementById("account-name").textContent = user.displayName;
    document.getElementById("account-role").textContent = user.role.replaceAll("_", " ");
    document.getElementById("account-avatar").textContent =
        (user.displayName || user.username || "U").slice(0, 1).toUpperCase();
    document.getElementById("store-name").textContent = state.storeName;
    document.querySelectorAll("[data-permission]").forEach(element => {
        element.hidden = !hasPermission(element.dataset.permission);
    });
    if (!hasPermissionForView(state.currentView)) state.currentView = "overview";
    selectView(state.currentView);
}

function hasPermissionForView(view) {
    const requirements = {
        inventory: "VIEW_PRODUCTS",
        transactions: "VIEW_INVENTORY",
        receipts: "PROCESS_SALES",
        users: "VIEW_USERS",
        settings: "MANAGE_SYSTEM_SETTINGS"
    };
    return !requirements[view] || hasPermission(requirements[view]);
}

function selectView(view) {
    if (!hasPermissionForView(view)) view = "overview";
    state.currentView = view;
    document.querySelectorAll(".nav-item").forEach(button => {
        button.classList.toggle("active", button.dataset.view === view);
    });
    renderView().catch(showPageError);
}

function showPageError(error) {
    viewRoot.innerHTML = `<section class="error-container"><h2 class="error-title">Unable to load this section</h2><p class="error-message">${escapeHtml(error.message)}</p><button class="btn btn-secondary" data-action="refresh">Try again</button></section>`;
}

function heading(kicker, title, description, actions = "") {
    return `<div class="page-heading"><div><span class="eyebrow">${escapeHtml(kicker)}</span><h1>${escapeHtml(title)}</h1><p>${escapeHtml(description)}</p></div><div class="heading-actions">${actions}</div></div>`;
}

function panel(title, subtitle, content, action = "") {
    return `<section class="panel"><div class="panel-heading"><div><h2>${escapeHtml(title)}</h2><p>${escapeHtml(subtitle)}</p></div>${action}</div>${content}</section>`;
}

function emptyState(title, message) {
    return `<div class="empty-state"><strong>${escapeHtml(title)}</strong>${escapeHtml(message)}</div>`;
}

function button(label, action, id, style = "btn-secondary") {
    return `<button type="button" class="btn ${style}" data-action="${escapeHtml(action)}" data-id="${escapeHtml(id)}">${escapeHtml(label)}</button>`;
}

async function renderView() {
    viewRoot.innerHTML = `<div class="loading-state">Loading GhostPOS data…</div>`;
    switch (state.currentView) {
        case "inventory": return renderInventory();
        case "transactions": return renderTransactions();
        case "receipts": return renderReceipts();
        case "users": return renderUsers();
        case "settings": return renderSettings();
        default: return renderOverview();
    }
}

async function renderOverview() {
    const [products, sales] = await Promise.all([
        api("/products?activeOnly=true"),
        hasPermission("PROCESS_SALES") ? api("/sales?limit=8") : Promise.resolve([])
    ]);
    const lowStock = products.filter(product => product.stockQuantity <= product.lowStockThreshold);
    const totalUnits = products.reduce((sum, product) => sum + product.stockQuantity, 0);
    const today = new Date().toDateString();
    const todaysSales = sales.filter(sale => new Date(sale.createdAt).toDateString() === today);
    const revenue = todaysSales.reduce((sum, sale) => sum + Number(sale.total), 0);
    const recentRows = sales.slice(0, 5).map(sale => `
        <div class="quick-row"><span><strong>${escapeHtml(sale.saleNumber)}</strong><span class="secondary-cell">${escapeHtml(dateTime(sale.createdAt))}</span></span><strong>${money(sale.total)}</strong></div>
    `).join("");
    const lowStockRows = lowStock.slice(0, 5).map(product => `
        <div class="quick-row"><span>${escapeHtml(product.name)}<span class="secondary-cell">${escapeHtml(product.sku)}</span></span><span class="badge warning">${product.stockQuantity} left</span></div>
    `).join("");
    viewRoot.innerHTML = `
        ${heading("STORE OPERATIONS", "Overview", `Welcome back, ${state.user.displayName}. Here's the latest store activity.`)}
        <section class="stat-grid">
            <article class="stat-card"><span class="stat-label">Active products</span><strong class="stat-value">${products.length}</strong><span class="stat-note">Catalog items currently available</span></article>
            <article class="stat-card"><span class="stat-label">Units in stock</span><strong class="stat-value">${totalUnits}</strong><span class="stat-note">Across active products</span></article>
            <article class="stat-card"><span class="stat-label">Low stock</span><strong class="stat-value">${lowStock.length}</strong><span class="stat-note">At or below restock threshold</span></article>
            ${hasPermission("PROCESS_SALES") ? `<article class="stat-card"><span class="stat-label">Today's sales</span><strong class="stat-value">${money(revenue)}</strong><span class="stat-note">${todaysSales.length} recent sale${todaysSales.length === 1 ? "" : "s"} loaded</span></article>` : `<article class="stat-card"><span class="stat-label">Signed in as</span><strong class="stat-value">${escapeHtml(state.user.role.replaceAll("_", " "))}</strong><span class="stat-note">Access follows your assigned role</span></article>`}
        </section>
        <section class="dashboard-grid">
            ${panel("Low stock watch", "Items that need a restock review", lowStockRows || emptyState("Stock levels look good", "No active products are below their threshold."), button("Open inventory", "navigate", "inventory"))}
            ${hasPermission("PROCESS_SALES") ? panel("Recent receipts", "Latest completed sales", recentRows || emptyState("No sales yet", "Completed sales will appear here."), button("View sales", "navigate", "receipts")) : panel("Management tools", "Available workspaces", `<div class="quick-list"><div class="quick-row"><span>Inventory records</span><span class="badge">Available</span></div><div class="quick-row"><span>Stock activity</span><span class="badge">Available</span></div></div>`, button("Open inventory", "navigate", "inventory"))}
        </section>`;
}

async function renderInventory() {
    const [products, lowStock] = await Promise.all([
        api("/products?activeOnly=false"),
        api("/products/low-stock")
    ]);
    const addButton = hasPermission("MANAGE_PRODUCTS")
        ? button("＋ Add product", "new-product", "", "btn-primary")
        : "";
    const rows = products.map(product => {
        const stockBadge = product.stockQuantity <= product.lowStockThreshold
            ? `<span class="badge warning">${product.stockQuantity} low</span>`
            : `<span class="badge">${product.stockQuantity}</span>`;
        const actions = [
            hasPermission("MANAGE_PRODUCTS") ? button("Edit", "edit-product", product.id) : "",
            hasPermission("RECEIVE_STOCK") ? button("Receive", "stock-action", `${product.id}:receive`) : "",
            hasPermission("ADJUST_INVENTORY") ? button("Remove", "stock-action", `${product.id}:remove`) : "",
            hasPermission("ADJUST_INVENTORY") ? button("Adjust", "stock-action", `${product.id}:adjust`) : "",
            hasPermission("ADJUST_INVENTORY") ? button("Count", "stock-action", `${product.id}:count`) : ""
        ].join("");
        return `<tr>
            <td class="primary-cell">${escapeHtml(product.name)}<span class="secondary-cell">${escapeHtml(product.sku)}${product.barcode ? ` · ${escapeHtml(product.barcode)}` : ""}</span></td>
            <td>${money(product.price)}</td><td>${stockBadge}<span class="secondary-cell">threshold ${product.lowStockThreshold}</span></td>
            <td>${product.active ? `<span class="badge">Active</span>` : `<span class="badge inactive">Inactive</span>`}</td>
            <td><div class="table-actions">${actions || "—"}</div></td>
        </tr>`;
    }).join("");
    viewRoot.innerHTML = `
        ${heading("INVENTORY", "Product catalog", `${products.length} product${products.length === 1 ? "" : "s"} · ${lowStock.length} at or below reorder threshold`, addButton)}
        <div class="toolbar"><input id="inventory-search" class="search-input" type="search" placeholder="Search name, SKU, or barcode"><span class="badge">${lowStock.length} need attention</span></div>
        ${panel("Products", "Pricing, availability and stock on hand", `<div class="table-scroll"><table class="data-table"><thead><tr><th>Product</th><th>Price</th><th>Stock</th><th>Status</th><th>Actions</th></tr></thead><tbody id="inventory-rows">${rows || `<tr><td colspan="5">${emptyState("No products found", "Create a product to get started.")}</td></tr>`}</tbody></table></div>`)}
    `;
}

async function renderTransactions() {
    const transactions = await api("/inventory/transactions?limit=200");
    const rows = transactions.map(transaction => `
        <tr>
            <td class="primary-cell">#${transaction.id}<span class="secondary-cell">${escapeHtml(dateTime(transaction.createdAt))}</span></td>
            <td>${transaction.productId}</td>
            <td><span class="badge">${escapeHtml(transaction.transactionType)}</span></td>
            <td>${transaction.quantityDelta > 0 ? "+" : ""}${transaction.quantityDelta}</td>
            <td>${escapeHtml(transaction.note || "—")}</td>
            <td>${escapeHtml(transaction.reference || "—")}</td>
            <td>${transaction.userId ?? "System"}</td>
        </tr>
    `).join("");
    viewRoot.innerHTML = `
        ${heading("INVENTORY", "Stock activity", "Auditable stock receipts, removals, counts and sale deductions.")}
        ${panel("Inventory transactions", "Most recent 200 stock movements", `<div class="table-scroll"><table class="data-table"><thead><tr><th>Record</th><th>Product ID</th><th>Type</th><th>Change</th><th>Note</th><th>Reference</th><th>User ID</th></tr></thead><tbody>${rows || `<tr><td colspan="7">${emptyState("No stock activity", "Inventory movements will be listed here.")}</td></tr>`}</tbody></table></div>`)}
    `;
}

async function renderReceipts() {
    const sales = await api("/sales?limit=200");
    const rows = sales.map(sale => `
        <tr>
            <td class="primary-cell">${escapeHtml(sale.saleNumber)}<span class="secondary-cell">${escapeHtml(dateTime(sale.createdAt))}</span></td>
            <td>${sale.userId}</td>
            <td>${money(sale.total)}</td>
            <td><span class="badge">${escapeHtml(sale.status)}</span></td>
            <td><div class="table-actions">${button("Open receipt", "open-receipt", sale.saleNumber)}</div></td>
        </tr>
    `).join("");
    viewRoot.innerHTML = `
        ${heading("SALES", "Sales & receipts", `${sales.length} recent receipt${sales.length === 1 ? "" : "s"} · Print-friendly receipt details`)}
        ${panel("Completed sales", "Select a sale to view or print its receipt", `<div class="table-scroll"><table class="data-table"><thead><tr><th>Sale</th><th>Cashier ID</th><th>Total</th><th>Status</th><th>Receipt</th></tr></thead><tbody>${rows || `<tr><td colspan="5">${emptyState("No sales recorded", "Completed sales will appear here.")}</td></tr>`}</tbody></table></div>`)}
    `;
}

async function renderUsers() {
    const users = await api("/users");
    const addButton = hasPermission("MANAGE_USERS")
        ? button("＋ Add user", "new-user", "", "btn-primary")
        : "";
    const rows = users.map(user => {
        const actions = hasPermission("MANAGE_USERS") ? [
            button("Role", "edit-role", user.id),
            button(user.active ? "Deactivate" : "Activate", "toggle-user", user.id),
            button("Reset password", "reset-password", user.id)
        ].join("") : "";
        return `<tr>
            <td class="primary-cell">${escapeHtml(user.displayName)}<span class="secondary-cell">@${escapeHtml(user.username)}</span></td>
            <td><span class="badge">${escapeHtml(user.role.replaceAll("_", " "))}</span></td>
            <td>${user.active ? `<span class="badge">Active</span>` : `<span class="badge inactive">Inactive</span>`}</td>
            <td>${user.id}</td>
            <td><div class="table-actions">${actions || "—"}</div></td>
        </tr>`;
    }).join("");
    viewRoot.innerHTML = `
        ${heading("ACCESS CONTROL", "System users", `${users.length} account${users.length === 1 ? "" : "s"} · Role-based access`, addButton)}
        ${panel("User accounts", "Manage team access and account status", `<div class="table-scroll"><table class="data-table"><thead><tr><th>User</th><th>Role</th><th>Status</th><th>ID</th><th>Actions</th></tr></thead><tbody>${rows || `<tr><td colspan="5">${emptyState("No users found", "Create an account to invite a teammate.")}</td></tr>`}</tbody></table></div>`)}
    `;
}

async function renderSettings() {
    const settings = await api("/settings");
    state.storeName = settings.storeName;
    document.getElementById("store-name").textContent = settings.storeName;
    viewRoot.innerHTML = `
        ${heading("SYSTEM", "Settings", "Update the store identity shown across the management interface.")}
        <section class="panel settings-card">
            <div class="panel-heading"><div><h2>Store profile</h2><p>GhostPOS ${escapeHtml(settings.version)}</p></div></div>
            <p class="settings-note">Database credentials, API binding, and other operational secrets are intentionally not exposed through the management UI.</p>
            <form id="settings-form" class="inline-form">
                <div class="form-field"><label for="setting-store-name">Store name</label><input id="setting-store-name" name="storeName" maxlength="100" required value="${escapeHtml(settings.storeName)}"></div>
                <button class="btn btn-primary" type="submit">Save changes</button>
            </form>
        </section>`;
}

function showModal(title, subtitle, body) {
    modalContent.innerHTML = `
        <div class="modal-shell">
            <div class="modal-heading"><div><h2 id="modal-title">${escapeHtml(title)}</h2><p>${escapeHtml(subtitle)}</p></div><button class="modal-close" type="button" data-action="close-modal" aria-label="Close dialog">✕</button></div>
            <div class="modal-body">${body}</div>
        </div>`;
    modal.showModal();
}

function closeModal() {
    if (modal.open) modal.close();
}

function productForm(product = null) {
    const editing = Boolean(product);
    const value = key => escapeHtml(product?.[key] ?? "");
    showModal(editing ? "Edit product" : "Add product", "Product prices and details are stored by GhostPOS.", `
        <form id="product-form" data-id="${editing ? product.id : ""}">
            <div class="form-grid">
                <div class="form-field"><label>SKU</label><input name="sku" maxlength="100" required value="${value("sku")}"></div>
                <div class="form-field"><label>Barcode</label><input name="barcode" maxlength="100" value="${value("barcode")}"></div>
                <div class="form-field full"><label>Product name</label><input name="name" maxlength="200" required value="${value("name")}"></div>
                <div class="form-field full"><label>Description</label><textarea name="description">${value("description")}</textarea></div>
                <div class="form-field"><label>Price</label><input name="price" type="number" step="0.01" min="0" required value="${value("price")}"></div>
                <div class="form-field"><label>Unit cost</label><input name="cost" type="number" step="0.01" min="0" value="${value("cost")}"></div>
                <div class="form-field"><label>Low-stock threshold</label><input name="lowStockThreshold" type="number" min="0" step="1" required value="${value("lowStockThreshold") || "0"}"></div>
                ${editing ? `<div class="form-field"><label>Status</label><select name="active"><option value="true" ${product.active ? "selected" : ""}>Active</option><option value="false" ${!product.active ? "selected" : ""}>Inactive</option></select></div>` : `<input type="hidden" name="active" value="true">`}
            </div>
            ${!editing ? `<p class="form-help">New items start with zero stock. Use Receive stock to record opening inventory.</p>` : ""}
            <div class="modal-actions"><button class="btn btn-secondary" type="button" data-action="close-modal">Cancel</button><button class="btn btn-primary" type="submit">${editing ? "Save product" : "Create product"}</button></div>
        </form>`);
}

function stockForm(productId, action) {
    const titleByAction = { receive: "Receive stock", remove: "Remove stock", adjust: "Adjust stock", count: "Record stock count" };
    const fields = action === "receive"
        ? `<div class="form-field"><label>Quantity received</label><input name="quantity" type="number" min="1" step="1" required></div><div class="form-field full"><label>Reason / delivery note</label><input name="note" maxlength="500" required></div>`
        : action === "remove"
            ? `<div class="form-field"><label>Quantity removed</label><input name="quantity" type="number" min="1" step="1" required></div><div class="form-field"><label>Reason</label><select name="reason" required><option value="DAMAGE">Damage</option><option value="WASTE">Waste</option><option value="THEFT">Theft</option><option value="RETURN_TO_SUPPLIER">Return to supplier</option><option value="OTHER">Other</option></select></div><div class="form-field full"><label>Note</label><input name="note" maxlength="500" required></div>`
            : action === "count"
                ? `<div class="form-field"><label>Counted quantity</label><input name="countedQuantity" type="number" min="0" step="1" required></div><div class="form-field full"><label>Count note</label><input name="note" maxlength="500" required></div>`
                : `<div class="form-field"><label>Quantity change</label><input name="quantityDelta" type="number" step="1" required><p class="form-help">Use a positive number to add stock, negative to remove it.</p></div><div class="form-field full"><label>Reason</label><input name="reason" maxlength="500" required></div>`;
    showModal(titleByAction[action], "Every inventory change is recorded with your user ID.", `
        <form id="stock-form" data-id="${escapeHtml(productId)}" data-operation="${escapeHtml(action)}">
            <div class="form-grid">${fields}<div class="form-field full"><label>Reference (optional)</label><input name="reference" maxlength="120"></div></div>
            <div class="modal-actions"><button class="btn btn-secondary" type="button" data-action="close-modal">Cancel</button><button class="btn btn-primary" type="submit">Save stock record</button></div>
        </form>`);
}

function availableRoles() {
    if (state.user.role === "ADMIN") return ["ADMIN", "GENERAL_MANAGER", "MANAGER", "TEAM_LEAD", "USER"];
    if (state.user.role === "GENERAL_MANAGER") return ["MANAGER", "TEAM_LEAD", "USER"];
    if (state.user.role === "MANAGER") return ["TEAM_LEAD", "USER"];
    return [];
}

function userCreateForm() {
    const options = availableRoles().map(role => `<option value="${role}">${escapeHtml(role.replaceAll("_", " "))}</option>`).join("");
    showModal("Add system user", "Passwords must be at least 12 characters.", `
        <form id="user-create-form"><div class="form-grid">
            <div class="form-field"><label>Username</label><input name="username" minlength="3" maxlength="64" required autocomplete="off"></div>
            <div class="form-field"><label>Role</label><select name="role" required>${options}</select></div>
            <div class="form-field full"><label>Display name</label><input name="displayName" maxlength="200" required></div>
            <div class="form-field full"><label>Temporary password</label><input name="password" type="password" minlength="12" maxlength="72" required autocomplete="new-password"></div>
        </div><div class="modal-actions"><button class="btn btn-secondary" type="button" data-action="close-modal">Cancel</button><button class="btn btn-primary" type="submit">Create user</button></div></form>`);
}

async function roleForm(id) {
    const users = await api("/users");
    const target = users.find(user => user.id === Number(id));
    if (!target) throw new Error("User no longer exists");
    const options = availableRoles().map(role => `<option value="${role}" ${role === target.role ? "selected" : ""}>${escapeHtml(role.replaceAll("_", " "))}</option>`).join("");
    showModal("Change user role", `Update access for ${target.displayName}.`, `
        <form id="role-form" data-id="${target.id}"><div class="form-field"><label>New role</label><select name="role" required>${options}</select></div>
        <div class="modal-actions"><button class="btn btn-secondary" type="button" data-action="close-modal">Cancel</button><button class="btn btn-primary" type="submit">Update role</button></div></form>`);
}

function passwordResetForm(id) {
    showModal("Reset password", "Set a new password of at least 12 characters. The user must sign in again.", `
        <form id="password-reset-form" data-id="${escapeHtml(id)}"><div class="form-field"><label>New password</label><input name="newPassword" type="password" minlength="12" maxlength="72" required autocomplete="new-password"></div>
        <div class="modal-actions"><button class="btn btn-secondary" type="button" data-action="close-modal">Cancel</button><button class="btn btn-primary" type="submit">Reset password</button></div></form>`);
}

async function showReceipt(saleNumber) {
    const receipt = await api(`/sales/${encodeURIComponent(saleNumber)}/receipt`);
    const sale = receipt.sale;
    const itemRows = receipt.items.map(item => `
        <div class="receipt-row"><span>${item.quantity} × ${escapeHtml(item.productName)}<span class="secondary-cell">${escapeHtml(item.sku)} · ${money(item.unitPrice)} each</span></span><strong>${money(Number(item.unitPrice) * item.quantity - Number(item.discountAmount || 0))}</strong></div>
    `).join("");
    const tenderRows = receipt.tenders.map(tender => `
        <div class="receipt-row"><span>${escapeHtml(tender.method)}${tender.reference ? `<span class="secondary-cell">Ref ${escapeHtml(tender.reference)}</span>` : ""}</span><strong>${money(tender.amount)}</strong></div>
    `).join("");
    showModal("Sale receipt", `Receipt ${sale.saleNumber} · ${dateTime(receipt.issuedAt)}`, `
        <article class="receipt-paper">
            <h2 class="receipt-title">${escapeHtml(receipt.storeName)}</h2>
            <p class="receipt-meta">GhostPOS · ${escapeHtml(receipt.systemVersion)}</p>
            <p class="receipt-meta">${escapeHtml(sale.saleNumber)} · ${escapeHtml(dateTime(receipt.issuedAt))}</p>
            <hr class="receipt-rule">${itemRows}<hr class="receipt-rule">
            <div class="receipt-row"><span>Subtotal</span><strong>${money(sale.subtotal)}</strong></div>
            <div class="receipt-row"><span>Discount</span><strong>−${money(sale.discountAmount)}</strong></div>
            <div class="receipt-row"><span>Tax</span><strong>${money(sale.taxAmount)}</strong></div>
            <div class="receipt-row receipt-total"><span>Total</span><strong>${money(sale.total)}</strong></div>
            <hr class="receipt-rule"><div class="receipt-row"><strong>Tenders · recorded only</strong></div>${tenderRows}
            <div class="receipt-row"><span>Cash change due</span><strong>${money(receipt.cashChangeDue)}</strong></div>
            <p class="receipt-meta">Tender status: ${escapeHtml(receipt.tenderStatus)}</p>
        </article>
        <div class="modal-actions"><button class="btn btn-secondary" type="button" data-action="close-modal">Close</button><button class="btn btn-primary" type="button" data-action="print-receipt">Print receipt</button></div>`);
}

async function submitForm(form) {
    const data = new FormData(form);
    const values = Object.fromEntries(data.entries());
    const action = form.id;
    if (action === "settings-form") {
        const settings = await api("/settings", { method: "PATCH", body: { storeName: values.storeName } });
        state.storeName = settings.storeName;
        document.getElementById("store-name").textContent = settings.storeName;
        setNotice("Store settings saved.");
        return renderSettings();
    }
    if (action === "product-form") {
        const product = {
            sku: values.sku,
            barcode: values.barcode || null,
            name: values.name,
            description: values.description || "",
            categoryId: null,
            price: Number(values.price),
            cost: values.cost === "" ? 0 : Number(values.cost),
            lowStockThreshold: Number(values.lowStockThreshold),
            active: values.active === "true"
        };
        const id = form.dataset.id;
        await api(id ? `/products/${id}` : "/products", {
            method: id ? "PUT" : "POST",
            body: product
        });
        closeModal();
        setNotice(id ? "Product updated." : "Product created.");
        return renderInventory();
    }
    if (action === "stock-form") {
        const id = form.dataset.id;
        const operation = form.dataset.operation;
        const route = { receive: "receive", remove: "remove", adjust: "adjust", count: "count" }[operation];
        const body = {
            quantity: values.quantity ? Number(values.quantity) : undefined,
            countedQuantity: values.countedQuantity ? Number(values.countedQuantity) : undefined,
            quantityDelta: values.quantityDelta ? Number(values.quantityDelta) : undefined,
            reason: values.reason || undefined,
            note: values.note || undefined,
            reference: values.reference || null
        };
        await api(`/inventory/${id}/${route}`, { method: "POST", body });
        closeModal();
        setNotice("Inventory transaction recorded.");
        return renderInventory();
    }
    if (action === "user-create-form") {
        await api("/users", { method: "POST", body: values });
        closeModal();
        setNotice("User account created.");
        return renderUsers();
    }
    if (action === "role-form") {
        await api(`/users/${form.dataset.id}/role`, { method: "PATCH", body: { role: values.role } });
        closeModal();
        setNotice("User role updated.");
        return renderUsers();
    }
    if (action === "password-reset-form") {
        await api(`/users/${form.dataset.id}/password-reset`, {
            method: "POST",
            body: { newPassword: values.newPassword }
        });
        closeModal();
        setNotice("Password reset. Existing sessions were revoked.");
        return renderUsers();
    }
}

document.getElementById("login-form").addEventListener("submit", async event => {
    event.preventDefault();
    const error = document.getElementById("login-error");
    error.textContent = "";
    const form = new FormData(event.currentTarget);
    try {
        const result = await api("/auth/login", {
            method: "POST",
            body: { username: form.get("username"), password: form.get("password") }
        });
        state.token = result.token;
        sessionStorage.setItem("ghostpos-token", result.token);
        showApplication(result.user, result.permissions);
    } catch (requestError) {
        error.textContent = requestError.message;
    }
});

document.getElementById("logout-button").addEventListener("click", async () => {
    try {
        await api("/auth/logout", { method: "POST" });
    } catch {
        // A server-side session may already have expired; clearing the local token is still safe.
    } finally {
        clearSession();
        showLogin();
    }
});

document.querySelector(".side-navigation").addEventListener("click", event => {
    const button = event.target.closest("[data-view]");
    if (button && !button.hidden) selectView(button.dataset.view);
});

viewRoot.addEventListener("input", event => {
    if (event.target.id !== "inventory-search") return;
    const query = event.target.value.trim().toLocaleLowerCase();
    document.querySelectorAll("#inventory-rows tr").forEach(row => {
        row.hidden = !row.textContent.toLocaleLowerCase().includes(query);
    });
});

viewRoot.addEventListener("click", async event => {
    const actionButton = event.target.closest("[data-action]");
    if (!actionButton) return;
    const { action, id } = actionButton.dataset;
    try {
        if (action === "navigate") return selectView(id);
        if (action === "refresh") return renderView();
        if (action === "new-product") return productForm();
        if (action === "edit-product") {
            const product = (await api("/products?activeOnly=false")).find(item => item.id === Number(id));
            if (!product) throw new Error("Product no longer exists");
            return productForm(product);
        }
        if (action === "stock-action") {
            const [productId, operation] = id.split(":");
            return stockForm(productId, operation);
        }
        if (action === "new-user") return userCreateForm();
        if (action === "edit-role") return await roleForm(id);
        if (action === "reset-password") return passwordResetForm(id);
        if (action === "toggle-user") {
            const user = (await api("/users")).find(item => item.id === Number(id));
            if (!user) throw new Error("User no longer exists");
            const verb = user.active ? "deactivate" : "activate";
            if (!window.confirm(`Are you sure you want to ${verb} ${user.displayName}?`)) return;
            await api(`/users/${id}/active`, { method: "PATCH", body: { active: !user.active } });
            setNotice(`Account ${user.active ? "deactivated" : "activated"}.`);
            return renderUsers();
        }
        if (action === "open-receipt") return await showReceipt(id);
    } catch (error) {
        setNotice(error.message, true);
    }
});

viewRoot.addEventListener("submit", async event => {
    const form = event.target.closest("form");
    if (!form) return;
    event.preventDefault();
    const submit = form.querySelector('[type="submit"]');
    if (submit) submit.disabled = true;
    try {
        await submitForm(form);
    } catch (error) {
        setNotice(error.message, true);
    } finally {
        if (submit?.isConnected) submit.disabled = false;
    }
});

modal.addEventListener("click", event => {
    if (event.target === modal) closeModal();
    const action = event.target.closest("[data-action]")?.dataset.action;
    if (action === "close-modal") closeModal();
    if (action === "print-receipt") window.print();
});

async function start() {
    try {
        const configResponse = await fetch("/ui-config.json", { headers: { Accept: "application/json" } });
        if (!configResponse.ok) throw new Error("Management UI configuration could not be loaded");
        const config = await configResponse.json();
        state.apiBaseUrl = config.apiBaseUrl.replace(/\/+$/, "");
        if (!state.token) return showLogin();
        try {
            const current = await api("/auth/me");
            showApplication(current.user, current.permissions);
        } catch (error) {
            clearSession();
            showLogin(error.message);
        }
    } catch (error) {
        showLogin(error.message);
    }
}

start();
