export type MenuItem = {
  id: number
  name: string
  price: number
  soldOut: boolean
  imageUrl?: string | null
  visible: boolean
  category?: 'MAIN' | 'SIDE' | 'DRINK' | null
}
