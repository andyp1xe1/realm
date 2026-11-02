import type { Link, OwnerCtx, RealmCtx } from "../../src/common.ts";
import { list } from "../../src/items.ts";

declare const owner: OwnerCtx;
declare const inRealm: RealmCtx;
declare const link: Link;

list(inRealm);
// @ts-expect-error
list(owner);
inRealm.realm.id.toString();
// @ts-expect-error
owner.realm.id.toString();
export const picked: boolean = link.selection;
// @ts-expect-error
export const raw: number = link.selection;
