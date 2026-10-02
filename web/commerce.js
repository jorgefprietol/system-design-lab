const byId = id => document.getElementById(id);
const money = cents => new Intl.NumberFormat('es-EC', {style:'currency',currency:'USD'}).format(cents / 100);
let lastOrder, busy = false;
const errors = {
  out_of_stock:'No quedan suficientes unidades. El pedido no se guardó.',
  idempotency_conflict:'La referencia ya corresponde a otro producto o cantidad.',
  commerce_not_configured:'Esta instancia todavía no tiene habilitada la tienda compartida.',
  commerce_unavailable:'La tienda está temporalmente sin conexión. Conserva la referencia y reintenta.',
  product_not_found:'El producto no existe.',
  invalid_quantity:'La cantidad debe ser un entero entre 1 y 100.'
};
async function request(path, payload) {
  const response = await fetch('/api/commerce/' + path, payload ? {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)} : {});
  const result = await response.json();
  if (!response.ok) throw new Error(errors[result.error] || 'No se pudo completar la operación. Revisa los datos y reintenta.');
  return result;
}
function row(title, subtitle) {
  const item = document.createElement('div'); item.className = 'row';
  const strong = document.createElement('strong'); strong.textContent = title;
  const small = document.createElement('small'); small.textContent = subtitle;
  item.append(strong, small); return item;
}
async function refresh() {
  const health = await request('health');
  byId('runtime').textContent = health.implementation === 'java' ? 'Java · conectado' : 'C# · conectado';
  byId('instance').textContent = health.instance;
  if (health.peerUrl) { const peer = new URL(health.peerUrl); if (['http:','https:'].includes(peer.protocol)) { byId('peer').href = peer.href; byId('peer').hidden = false; } }
  const state = await request('state');
  const selected = byId('product').value;
  byId('product').replaceChildren(...state.products.map(product => { const option = document.createElement('option'); option.value = product.id; option.textContent = `${product.name} · ${money(product.priceCents)}`; return option; }));
  if (selected) byId('product').value = selected;
  byId('products').replaceChildren(...state.products.map(product => row(product.name, `${product.stock} unidades · ${money(product.priceCents)}`)));
  byId('orders').replaceChildren(...state.orders.slice(-8).reverse().map(order => row(order.key, `${order.quantity} × ${order.product} · ${money(order.totalCents)} · ${order.id.slice(0,12)}`)));
  if (!state.orders.length) byId('orders').append(row('Todavía no hay pedidos', 'Las compras aparecen en ambas instancias.'));
  byId('orders-count').textContent = state.orderCount; byId('units-count').textContent = state.unitsSold;
  byId('stock-count').textContent = state.products.reduce((sum, product) => sum + product.stock, 0);
  byId('store-status').textContent = 'Conectada'; byId('buy').disabled = busy;
}
function showError(error) { byId('notice').textContent = error.message; }
function newReference() { byId('reference').value = 'pedido-' + crypto.randomUUID().slice(0,12); }
async function submit(order) {
  if (busy) return;
  busy = true; byId('buy').disabled = true; byId('retry').disabled = true;
  lastOrder = structuredClone(order);
  try {
    const receipt = await request('orders', order);
    byId('notice').textContent = `Pedido ${receipt.id.slice(0,12)} confirmado · ${money(receipt.totalCents)}. La referencia recupera esta misma compra.`;
    await refresh();
  } catch (error) { showError(error); }
  finally { busy = false; byId('buy').disabled = false; byId('retry').disabled = !lastOrder; }
}
byId('order-form').addEventListener('submit', event => { event.preventDefault(); void submit({key:byId('reference').value,product:byId('product').value,quantity:Number(byId('quantity').value)}); });
byId('new-reference').addEventListener('click', newReference);
byId('retry').addEventListener('click', () => { if (lastOrder) void submit(lastOrder); });
byId('refresh').addEventListener('click', () => { void refresh().catch(showError); });
newReference();
void refresh().catch(error => { byId('store-status').textContent = 'Sin conexión'; showError(error); });
