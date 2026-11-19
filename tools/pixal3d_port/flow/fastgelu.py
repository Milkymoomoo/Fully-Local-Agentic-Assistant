# Replace the exported tanh-GELU chain  0.5·a·(1 + tanh(√(2/π)(a + 0.044715·a³)))  (8 elementwise ops over the MLP's
# [tokens × 4·C] tensor, each a full-size temporary) with com.microsoft FastGelu(x, bias) (same formula, one pass).
import onnx, sys
from onnx import helper
src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src, load_external_data=False); g = m.graph
prod = {o: n for n in g.node for o in n.output}
cons = {}
for n in g.node:
    for i in n.input: cons.setdefault(i, []).append(n)
dead = set(); new = {}; k = 0
for t in [n for n in g.node if n.op_type == "Tanh"]:
    # walk back: Tanh <- Mul_3 <- Add(a, Mul_2) ; a = input that is squared by Mul(a, a)
    m3 = prod[t.input[0]]; add = prod[[i for i in m3.input if i in prod and prod[i].op_type == "Add"][0]]
    sq = [n for n in g.node if n.op_type == "Mul" and n.input[0] == n.input[1] and n.input[0] in add.input]
    if not sq: print("skip", t.name); continue
    a = sq[0].input[0]
    # walk forward from tanh: Add_1 -> Mul_4 (a·) -> Mul_5 (0.5·)
    add1 = cons[t.output[0]][0]; mul4 = cons[add1.output[0]][0]; mul5 = cons[mul4.output[0]][0]
    assert add1.op_type == "Add" and mul4.op_type == "Mul" and mul5.op_type == "Mul", (add1.op_type, mul4.op_type, mul5.op_type)
    chain = set()
    def back(x):   # every node between a and mul5
        n = prod.get(x)
        if n is None or x == a or n.name in chain: return
        chain.add(n.name); [back(i) for i in n.input]
    back(mul5.output[0])
    pa = prod.get(a); inputs = [a]
    if pa is not None and pa.op_type == "Add" and len(cons[a]) == len([c for c in cons[a] if c.name in chain]):
        x, b = pa.input
        if any(i.name == b for i in g.initializer): inputs = [x, b]; chain.add(pa.name)
    dead |= chain
    new[mul5.name] = helper.make_node("FastGelu", inputs, [mul5.output[0]], name=f"fastgelu_{k}", domain="com.microsoft"); k += 1
nodes = []
for n in g.node:
    if n.name in new: nodes.append(new[n.name])
    elif n.name not in dead: nodes.append(n)
del g.node[:]; g.node.extend(nodes)
if not any(o.domain == "com.microsoft" for o in m.opset_import): m.opset_import.append(helper.make_opsetid("com.microsoft", 1))
print("fused", k, "removed", len(dead)); onnx.save(m, dst)
